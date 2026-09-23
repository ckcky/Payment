#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成系统设计文档的两类图：
  1) 状态图 stateDiagram-v2 —— 从 docs/architecture/systems/<svc>.md §5 的 ```text 迁移块忠实转换
  2) 存储图 flowchart      —— 从 deployment/schema/*.sql（表清单）+ application.yml（库/端口/Redis 用途）

只做「文本 → 图」的机械转换，不引入文档里没有的状态或迁移。
用法：python deployment/tools/gen-sysdoc-diagrams.py [--svc catalog-service]
"""

import os
import re
import argparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SYSDOC_DIR = os.path.join(ROOT, "docs", "architecture", "systems")
SCHEMA_DIR = os.path.join(ROOT, "deployment", "schema")
OUT_DIR = os.path.join(ROOT, "deployment", "output", "sysdiagrams")

SERVICES = [
    "merchant-service", "catalog-service", "order-service", "payment-service",
    "fulfillment-service", "entitlement-service", "reconciliation-service",
    "settlement-service", "ledger-service",
]

# 跨行分支写法的显式补丁：`|--x--> B` / `\--x--> B` 这类分支的源状态无法从单行文本推断，
# 由人工对照文档 §5 正文补齐。键 = (服务, 状态机块序号)。
# ⚠️ 这不是"编造"：每条都能在对应文档 §5 的正文行里逐字找到。
PATCH = {
    "merchant-service": {0: [
        ("PENDING_REVIEW", "terminate", "TERMINATED"),
        ("SUSPENDED", "terminate", "TERMINATED"),
    ]},
    "payment-service": {0: [
        ("PROCESSING", "fail", "FAILED"),
        ("PROCESSING", "markUnknown", "UNKNOWN"),
        ("UNKNOWN", "succeed", "SUCCEEDED"),
        ("UNKNOWN", "fail", "FAILED"),
    ]},
    "fulfillment-service": {0: [
        ("PROCESSING", "fail", "FAILED"),
    ]},
    "entitlement-service": {0: [
        ("AVAILABLE", "expire", "EXPIRED"),
        ("AVAILABLE", "revoke", "REVOKED"),
        ("AVAILABLE", "revokeForRefund", "REVOKED"),
        ("PARTIALLY_USED", "剩余==0", "EXHAUSTED"),
    ]},
    "reconciliation-service": {0: [
        ("RECONCILING", "finish(有差异)", "HAS_DIFFERENCE"),
    ]},
}

TOKEN_RE = re.compile(r"([A-Z][A-Z0-9_/]*)|-{2,}\s*([^->]*?)\s*-{2,}>")


def extract_section(md, start_pat, end_pat):
    i = md.find(start_pat)
    if i < 0:
        return ""
    j = md.find(end_pat, i)
    return md[i: j if j > 0 else len(md)]


def parse_block(block):
    """解析 ```text 块里的同行链式迁移。

    用 token 序列（状态名 / --label--> 交替）而不是直接正则抓三元组，
    这样 `A --x--> B --y--> C` 的中间边不会像非重叠匹配那样丢掉。

    只解析**同一行内**的迁移；跨行的分支写法（`|--x--> B`）无法从文本可靠推断源状态，
    由人工在生成结果上补齐（脚本会把这些行作为待补提示打印出来）。
    """
    out, unresolved = [], []
    for raw in block.splitlines():
        line = raw.strip()
        if not line or "-->" not in line:
            continue
        if line.startswith("|") or line.startswith("\\"):
            unresolved.append(line)
            continue
        line = re.sub(r"[（(][^）)]*[）)]", "", line)  # 去方法参数 / 中文注记
        toks = TOKEN_RE.findall(line)
        prev, lab = None, None
        for name, label in toks:
            if name:
                if prev and lab is not None:
                    for s in prev.split("/"):
                        for d in name.split("/"):
                            out.append((s, lab.strip(), d))
                prev, lab = name, None
            elif label:
                lab = label
    return out, unresolved


def split_blocks(sec5):
    """按 ```text 块切分，并取每块前最近的一个 **粗体名** 作为聚合标题。"""
    parts = []
    for m in re.finditer(r"```text\n(.*?)```", sec5, re.S):
        head = sec5[: m.start()]
        mt = None
        for mm in re.finditer(r"\*\*([^*]+)\*\*", head):
            mt = mm
        title = mt.group(1) if mt else None
        parts.append((title, m.group(1)))
    return parts


TABLE_ROW_RE = re.compile(r"^\|\s*`?([A-Z][A-Z0-9_]*)`?\s*(?:→|-->)\s*`?([A-Z][A-Z0-9_]*)`?\s*\|\s*([^|]*)\|")


def split_table_blocks(sec5):
    """没有 ```text 块时，退化为解析 `### 5.x` 小节里的迁移表格（如 ledger 的 LedgerPeriod）。"""
    parts = []
    # 按三级标题切分
    marks = [(m.start(), m.group(1)) for m in re.finditer(r"^### 5\.\d+\s*(.*)$", sec5, re.M)]
    marks.append((len(sec5), None))
    for i in range(len(marks) - 1):
        start, head = marks[i]
        end = marks[i + 1][0]
        seg = sec5[start:end]
        rows = []
        for line in seg.splitlines():
            m = TABLE_ROW_RE.match(line.strip())
            if m:
                rows.append((m.group(1), m.group(3).strip(), m.group(2)))
        if rows:
            mt = re.search(r"`([^`]+)`", head or "")
            parts.append((mt.group(1) if mt else (head or "").strip(), rows))
    return parts


def render_state(trans, title):
    if not trans:
        return None
    srcs = {t[0] for t in trans}
    dsts = {t[2] for t in trans}
    initials = sorted(srcs - dsts)
    lines = ["stateDiagram-v2"]
    for s in initials:
        lines.append("    [*] --> %s" % s)
    for src, label, dst in trans:
        # 去掉全角括号里的补充说明（如「关账请求（POST /periods/...）」）与 markdown 反引号；
        # 半角括号通常是方法参数（如 finish(有差异)），保留
        lab = re.sub(r"（[^）]*）", "", label).replace("`", "").strip()
        lab = re.sub(r"\s+", " ", lab)
        lines.append("    %s --> %s : %s" % (src, dst, lab if lab else "?"))
    return "\n".join(lines)


def scan_tables(db):
    """返回该库的 (表名, schema 文件) 列表。"""
    out, seen = [], set()
    for fn in sorted(os.listdir(SCHEMA_DIR)):
        if not fn.endswith(".sql"):
            continue
        src = open(os.path.join(SCHEMA_DIR, fn), encoding="utf-8", errors="replace").read()
        cur = None
        for m in re.finditer(r"(USE\s+`?(\w+)`?\s*;|CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?)", src, re.I):
            if m.group(2):
                cur = m.group(2)
            elif m.group(3) and cur == db and m.group(3) not in seen:
                seen.add(m.group(3))
                out.append((m.group(3), fn))
    return out


def read_yml(svc):
    p = os.path.join(ROOT, svc, "src", "main", "resources", "application.yml")
    if not os.path.exists(p):
        return "", ""
    return open(p, encoding="utf-8", errors="replace").read(), p


def render_storage(svc, db):
    yml, _ = read_yml(svc)
    host_port = "localhost:3306"
    m = re.search(r"jdbc:mysql://([^/]+)/(\w+)", yml)
    if m:
        host_port = m.group(1)
        if not db:
            db = m.group(2)
    # Redis 用途：抓 application.yml 里 redis 段上下的中文注释
    notes = []
    if "redis" in yml.lower():
        notes.append("缓存 / 事务消息通道")
    if re.search(r"pool:\s*\n\s*enabled:\s*true", yml):
        notes.append("连接池已启用")
    tables = scan_tables(db) if db else []

    lines = ["flowchart LR"]
    lines.append('    APP["%s<br/>应用层"]' % svc)
    if tables:
        lines.append('    subgraph DB["MySQL 8.0 · %s · %s 库"]' % (host_port, db))
        lines.append("        direction TB")
        for t, _f in tables:
            lines.append('        T_%s["%s"]' % (t, t))
        lines.append("    end")
        lines.append("    APP -->|MyBatis| DB")
    else:
        lines.append('    DB["MySQL：本服务无独立库<br/>（无表，纯编排/只读事实）"]')
        lines.append("    APP -->|无本地持久化| DB")
    lines.append('    subgraph RD["Redis 7 · localhost:6379"]')
    lines.append("        direction TB")
    lines.append('        R1["缓存 / 幂等键 / 事务消息通道<br/>（Redis Streams 模拟 MQ）"]')
    lines.append("    end")
    lines.append("    APP --> RD")
    return "\n".join(lines), tables


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--svc")
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)
    for svc in SERVICES:
        if args.svc and svc != args.svc:
            continue
        name = svc.replace("-service", "")
        doc = os.path.join(SYSDOC_DIR, svc + ".md")
        if not os.path.exists(doc):
            print("%-24s (无文档)" % svc)
            continue
        md = open(doc, encoding="utf-8").read()
        sec5 = extract_section(md, "## 5. 状态机", "\n## 6.")
        # 优先解析 ```text 迁移块；没有则退化解析迁移表格
        pairs = []
        for agg, blk in split_blocks(sec5):
            trans, unresolved = parse_block(blk)
            pairs.append((agg, trans, unresolved))
        if not pairs:
            for agg, rows in split_table_blocks(sec5):
                pairs.append((agg, rows, []))
        files = []
        total_t, total_u = 0, []
        for idx, (agg, trans, unresolved) in enumerate(pairs):
            trans = list(trans) + PATCH.get(svc, {}).get(idx, [])
            seen, uniq = set(), []
            for t in trans:
                if t not in seen:
                    seen.add(t)
                    uniq.append(t)
            if not uniq:
                continue
            st = render_state(uniq, "%s %s" % (name, agg or ""))
            suffix = ("-" + re.sub(r"\W+", "", agg).lower()) if agg and len(pairs) > 1 else ""
            fn = os.path.join(OUT_DIR, "%s-state%s.md" % (svc, suffix))
            with open(fn, "w", encoding="utf-8") as f:
                f.write("<!-- AUTO-GENERATED by deployment/tools/gen-sysdoc-diagrams.py — 请勿手改 -->\n")
                f.write("```mermaid\n" + st + "\n```\n")
            files.append((os.path.basename(fn), agg, len(uniq)))
            total_t += len(uniq)
            total_u += unresolved
        # 存储图
        db = None
        for cand in (name, {"order": "order", "catalog": "catalog", "payment": "payment",
                            "ledger": "ledger", "settlement": "settlement",
                            "reconciliation": "reconciliation", "fulfillment": "fulfillment",
                            "entitlement": "entitlement"}.get(name)):
            if cand and scan_tables(cand):
                db = cand
                break
        stg, tables = render_storage(svc, db)
        with open(os.path.join(OUT_DIR, svc + "-storage.md"), "w", encoding="utf-8") as f:
            f.write("<!-- AUTO-GENERATED by deployment/tools/gen-sysdoc-diagrams.py — 请勿手改 -->\n")
            f.write("```mermaid\n" + stg + "\n```\n")
        print("%-24s 迁移 %-3d 图 %-2d 表 %-3d db=%-15s 待补分支行 %d"
              % (svc, total_t, len(files), len(tables), db or "-", len(total_u)))
        if total_u:
            for u in total_u:
                print("      ⚠ 跨行分支待人工补：%s" % u.strip()[:70])


if __name__ == "__main__":
    main()
