#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把自动生成的图幂等注入 docs/architecture/systems/<svc>.md。

注入位置：
  - 模块分层图 → §3 末尾，新增小节 `### 3.N 内部分层与模块`
  - ER 图       → §4 末尾，新增小节 `### 4.N 表关系（ER 图）`
  - 状态图      → §5 末尾，新增小节 `### 5.N 状态迁移图`
  - 存储图      → §10 末尾，新增小节 `### 10.N 存储拓扑`

幂等：以 `<!-- diagram:xxx --> ... <!-- /diagram:xxx -->` 包裹，重复运行只替换块内内容。
用法：python deployment/tools/insert-system-diagrams.py [--check]
"""

import os
import re
import sys
import argparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SYSDOC = os.path.join(ROOT, "docs", "architecture", "systems")
GEN = os.path.join(ROOT, "deployment", "output", "sysdiagrams")

SERVICES = [
    "merchant-service", "catalog-service", "order-service", "payment-service",
    "fulfillment-service", "entitlement-service", "reconciliation-service",
    "settlement-service", "ledger-service",
]


def read_mermaid(path):
    """取生成文件里的 mermaid 代码块（含 AUTO-GENERATED 注释）。"""
    if not os.path.exists(path):
        return None
    s = open(path, encoding="utf-8").read()
    m = re.search(r"(<!-- AUTO-GENERATED.*?-->\n)?```mermaid\n.*?\n```", s, re.S)
    return m.group(0) if m else None


def section_range(md, heading_re):
    """返回 (标题行起始, 本章节结束位置)。"""
    m = re.search(heading_re, md, re.M)
    if not m:
        return None
    start = m.start()
    lvl = len(m.group(1)) if m.group(1) else 2
    # 找下一个同级或更高级标题
    pat = re.compile(r"^(#{1,%d}) " % lvl, re.M)
    nxt = pat.search(md, m.end())
    return start, (nxt.start() if nxt else len(md))


def max_sub(md, sec_pos, sec_end, num):
    """某章下已有的 `### N.M` 最大子号。"""
    seg = md[sec_pos:sec_end]
    subs = [int(m.group(1)) for m in re.finditer(r"^### %d\.(\d+)" % num, seg, re.M)]
    return max(subs) if subs else 0


MARK = "<!-- diagram:%s -->"
END = "<!-- /diagram:%s -->"


def upsert(md, anchor_pos, key, block):
    """在 anchor_pos 处插入或替换标记块。"""
    m = re.search(re.escape(MARK % key) + r".*?" + re.escape(END % key), md, re.S)
    if m:
        md = md[: m.start()] + block + md[m.end():]
        return md, "替换"
    md = md[:anchor_pos] + block + md[anchor_pos:]
    return md, "新增"


def build_block(key, intro, mermaid):
    return "\n%s\n\n%s\n%s\n%s\n\n" % (
        intro,
        MARK % key,
        mermaid,
        END % key,
    )


def process(svc, check_only=False):
    name = svc.replace("-service", "")
    doc = os.path.join(SYSDOC, svc + ".md")
    if not os.path.exists(doc):
        return "%s (无文档)" % svc
    md = open(doc, encoding="utf-8").read()
    orig = md
    actions = []

    # ---------- 1. 模块分层图 → §3 末尾 ----------
    r = section_range(md, r"^(##) 3\.")
    if r:
        pos, end = r
        n = max_sub(md, pos, end, 3) + 1
        mmd = read_mermaid(os.path.join(GEN, svc + "-module.md"))
        if mmd:
            sec = (
                "### 3.%d 内部分层与模块\n\n"
                "下图由 `deployment/tools/gen-module-diagrams.py` 扫描 `%s/src/main/java` **自动生成**，"
                "反映真实的包与类分布（DTO 不计入）。\n" % (n, svc)
            )
            blk = build_block(
                "module",
                sec,
                mmd,
            )
            md, act = upsert(md, end, "module", blk.rstrip() + "\n\n")
            actions.append("模块图" + act)

    # ---------- 2. ER 图 → §4 末尾 ----------
    r = section_range(md, r"^(##) 4\.")
    if r:
        pos, end = r
        n = max_sub(md, pos, end, 4) + 1
        db = name if os.path.exists(os.path.join(GEN, "..", "er", name + ".md")) else None
        mmd = read_mermaid(os.path.join(ROOT, "deployment", "output", "er", name + ".md"))
        if mmd:
            sec = (
                "### 4.%d 表关系（ER 图）\n\n"
                "由 `deployment/tools/gen-er-diagrams.py` 从 `deployment/schema/*.sql` 解析**自动生成**，"
                "字段与真实 Schema 一致。\n"
                "⚠️ 图内只出现**同库**关系：跨服务引用一律走业务单号、不建外键"
                "（[ADR-0063](../../adr/0063-cross-service-reference-by-business-no.md)），"
                "因此不存在跨库连线。\n" % n
            )
            blk = build_block("er", sec, mmd)
            md, act = upsert(md, end, "er", blk.rstrip() + "\n\n")
            actions.append("ER 图" + act)

    # ---------- 3. 状态图 → §5 末尾 ----------
    r = section_range(md, r"^(##) 5\.")
    if r:
        pos, end = r
        n = max_sub(md, pos, end, 5) + 1
        # 收集该服务的全部状态图（可能多张，按聚合分）
        cands = []
        single = os.path.join(GEN, svc + "-state.md")
        if os.path.exists(single):
            cands.append((None, single))
        else:
            for fn in sorted(os.listdir(GEN)) if os.path.isdir(GEN) else []:
                if fn.startswith(svc + "-state-") and fn.endswith(".md"):
                    agg = fn[len(svc) + 7: -3]
                    cands.append((agg, os.path.join(GEN, fn)))
        if cands:
            parts = []
            for agg, path in cands:
                mmd = read_mermaid(path)
                if not mmd:
                    continue
                if agg:
                    parts.append("**%s**\n\n%s" % (agg, mmd))
                else:
                    parts.append(mmd)
            if parts:
                sec = (
                    "### 5.%d 状态迁移图\n\n"
                    "由 `deployment/tools/gen-sysdoc-diagrams.py` 从**本章上文的迁移文本**转换而来，"
                    "不引入文中没有的状态或迁移。\n"
                ) % n
                body = "\n\n".join(parts)
                blk = "\n%s\n\n%s\n%s\n%s\n\n" % (sec, MARK % "state", body, END % "state")
                md, act = upsert(md, end, "state", blk)
                actions.append("状态图" + act)

    # ---------- 4. 存储图 → §10 末尾 ----------
    r = section_range(md, r"^(##) 10\.")
    if r:
        pos, end = r
        n = max_sub(md, pos, end, 10) + 1
        mmd = read_mermaid(os.path.join(GEN, svc + "-storage.md"))
        if mmd:
            sec = (
                "### 10.%d 存储拓扑\n\n"
                "由 `deployment/tools/gen-sysdoc-diagrams.py` 依据 `application.yml` 的数据源/Redis 配置"
                "与 `deployment/schema/*.sql` 的表清单**自动生成**。\n" % n
            )
            blk = build_block("storage", sec, mmd)
            md, act = upsert(md, end, "storage", blk.rstrip() + "\n\n")
            actions.append("存储图" + act)

    if check_only:
        return "%-24s %s" % (svc, " / ".join(actions) if actions else "(无变化)")
    if md != orig:
        open(doc, "w", encoding="utf-8", newline="\n").write(md)
    return "%-24s %s" % (svc, " / ".join(actions) if actions else "(无变化)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只报告，不写文件")
    args = ap.parse_args()
    for svc in SERVICES:
        print(process(svc, args.check))


if __name__ == "__main__":
    main()
