#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描服务源码，生成「模块分层图」（Mermaid flowchart）——系统设计文档 §4/§10 用。

规则：只反映代码里真实存在的东西（包、类、schema、Redis/MySQL 配置），不编造模块。
用法：
    python deployment/tools/gen-module-diagrams.py            # 全部服务 → deployment/output/sysdiagrams/
    python deployment/tools/gen-module-diagrams.py --stdout payment-service
"""

import os
import re
import json
import argparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "deployment", "output", "sysdiagrams")
SCHEMA_DIR = os.path.join(ROOT, "deployment", "schema")

SERVICES = [
    "merchant-service",
    "catalog-service",
    "order-service",
    "payment-service",
    "fulfillment-service",
    "entitlement-service",
    "reconciliation-service",
    "settlement-service",
    "ledger-service",
]

# 路径段 → 架构层（取路径中最后一个匹配的段）
LAYER_BY_SEG = {
    "web": "inbound",
    "api": "inbound",
    "application": "app",
    "domain": "domain",
    "infra": "infra",
    "persistence": "infra",
    "mq": "infra",
    "cache": "infra",
    "client": "infra",
    "config": "infra",
}

LAYER_TITLE = {
    "inbound": "入口层 · web / api（HTTP 入口与 DTO 转换）",
    "app": "应用层 · application（用例编排）",
    "domain": "领域层 · domain（聚合根 / 状态机 / 不变式 / 仓储端口）",
    "infra": "基础设施层 · infra / persistence / mq / cache（实现端口与持久化）",
}

# 类名后缀 → 层（路径无层关键词时回落）
SUFFIX_RULES = [
    ("Controller", "inbound"),
    ("ApplicationService", "app"),
    ("Application", "skip"),
    ("Service", "app"),
    ("RuleRegistry", "domain"),
    ("Rule", "domain"),
    ("Status", "domain"),
    ("Type", "domain"),
    ("Entity", "infra"),
    ("Mapper", "infra"),
    ("Config", "infra"),
    ("Properties", "infra"),
    ("FeignClient", "infra"),
    ("Client", "infra"),
    ("Gateway", "infra"),
    ("Adapter", "infra"),
    ("Publisher", "infra"),
    ("Consumer", "infra"),
    ("Cache", "infra"),
    ("CacheView", "infra"),
    ("Repository", "domain"),
]

SKIP_SUFFIX = ("Request", "Response", "Dto", "DTO", "Vo", "Event", "Message", "Result")
DOMAIN_PKG_HINT = {
    "posting",
    "rules",
    "domain",
    "limit",
    "audit",
    "statement",
    "reconciliation",
    "settlement",
    "posting",
}


def classify(rel):
    """rel 形如 com/payment/catalog/product/ProductEntity"""
    segs = rel.split("/")
    cls = segs[-1]
    pkg_segs = segs[1:-1]  # 去掉 com、payment
    if cls.endswith(SKIP_SUFFIX) and not cls.endswith("Status"):
        return None
    # 1) 路径段里最后一个层关键词
    layer = None
    for s in pkg_segs:
        if s in LAYER_BY_SEG:
            layer = LAYER_BY_SEG[s]
    if layer:
        return layer
    # 2) 类名后缀
    for suf, lay in SUFFIX_RULES:
        if cls.endswith(suf):
            return None if lay == "skip" else lay
    # 3) 领域包提示
    for s in pkg_segs:
        if s in DOMAIN_PKG_HINT:
            return "domain"
    return "domain"


def scan_service(svc):
    base = os.path.join(ROOT, svc, "src", "main", "java")
    found = {}
    if not os.path.isdir(base):
        return found
    for dirpath, _, files in os.walk(base):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, base).replace("\\", "/")[:-5]
            layer = classify(rel)
            if not layer:
                continue
            cls = rel.split("/")[-1]
            # 记录所在子包（用于分组标签）
            pkg_segs = rel.split("/")[1:-1]
            sub = ""
            for s in pkg_segs:
                if s not in ("web", "api", "dto", "application", "domain", "infra",
                             "persistence", "mq", "cache", "client", "config"):
                    if s not in ("catalog", "order", "payment", "ledger", "settlement",
                                 "reconciliation", "fulfillment", "entitlement", "merchant"):
                        sub = s
            found.setdefault(layer, []).append((sub, cls))
    for k in found:
        found[k].sort()
    return found


def scan_schema_dbs(svc):
    """本服务拥有的库（从 schema 的 USE 归属推断）。"""
    dbs = set()
    for fn in sorted(os.listdir(SCHEMA_DIR)):
        if not fn.endswith(".sql"):
            continue
        src = open(os.path.join(SCHEMA_DIR, fn), encoding="utf-8", errors="replace").read()
        for m in re.finditer(r"USE\s+`?(\w+)`?\s*;", src, re.I):
            dbs.add(m.group(1))
    # 服务名 → 库名（merchant 无独立库）
    name = svc.replace("-service", "")
    alias = {"order": "order", "catalog": "catalog", "payment": "payment", "ledger": "ledger",
             "settlement": "settlement", "reconciliation": "reconciliation",
             "fulfillment": "fulfillment", "entitlement": "entitlement"}
    db = alias.get(name)
    return db if db in dbs else None


def scan_feign_clients(svc):
    """出站依赖：*FeignClient / *Client 接口。"""
    base = os.path.join(ROOT, svc, "src", "main", "java")
    out = []
    if not os.path.isdir(base):
        return out
    for dirpath, _, files in os.walk(base):
        for fn in files:
            if fn.endswith("Client.java") or fn.endswith("FeignClient.java"):
                out.append(fn[:-5])
    return sorted(set(out))



def render(svc, layers, db, feign):
    """紧凑形态：每层压成**一个**节点（组间用 ｜ 分隔，行内折行），避免竖着长出 5000px。"""
    lines = ["flowchart TB"]

    def fmt(items, cap_per_group=3, line_chars=86):
        # 先按子包聚合成「组名：类1 · 类2 · …」
        parts = []
        for gname, clss in sorted(group_of(items).items()):
            shown = " · ".join(clss[:cap_per_group])
            more = " …" if len(clss) > cap_per_group else ""
            parts.append(shown + more if gname == "_" else "%s：%s%s" % (gname, shown, more))
        # 再折行
        out, cur, curlen = [], [], 0
        for p in parts:
            if cur and curlen + len(p) > line_chars:
                out.append(" ｜ ".join(cur))
                cur, curlen = [], 0
            cur.append(p)
            curlen += len(p) + 3
        if cur:
            out.append(" ｜ ".join(cur))
        return "<br/>".join(out)

    ids = {}
    for key in ("inbound", "app", "domain", "infra"):
        items = layers.get(key, [])
        if not items:
            continue
        nid = key.upper()
        ids[key] = nid
        lines.append('    %s["%s<br/>%s"]' % (nid, LAYER_TITLE[key], fmt(items)))

    chain = [k for k in ("inbound", "app", "domain") if ids.get(k)]
    for a, b in zip(chain, chain[1:]):
        lines.append("    %s --> %s" % (ids[a], ids[b]))
    if ids.get("infra"):
        src = ids.get("domain") or ids.get("app") or ids.get("inbound")
        lines.append("    %s -.->|端口实现| %s" % (src, ids["infra"]))

    if db:
        lines.append('    DB[("MySQL<br/>%s 库")]' % db)
        lines.append("    %s --> DB" % ids["infra"])
    lines.append('    REDIS[("Redis<br/>缓存 / Redis Streams 事务消息")]')
    lines.append("    %s --> REDIS" % ids["infra"])

    for c in feign:
        cid = re.sub(r"\W", "", c)
        lines.append('    %s["%s"]' % (cid, c))
        lines.append("    %s -.->|Feign RPC| %s" % (ids["infra"], cid))

    return "\n".join(lines)


def group_of(items):
    g = {}
    for sub, cls in items:
        g.setdefault(sub or "_", []).append(cls)
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stdout", help="只打印指定服务")
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)
    for svc in SERVICES:
        layers = scan_service(svc)
        if not layers:
            print("%-24s (无源码)" % svc)
            continue
        db = scan_schema_dbs(svc)
        feign = scan_feign_clients(svc)
        txt = render(svc, layers, db, feign)
        if args.stdout:
            if svc == args.stdout:
                print(txt)
            continue
        with open(os.path.join(OUT_DIR, svc + "-module.md"), "w", encoding="utf-8") as f:
            f.write("<!-- AUTO-GENERATED by deployment/tools/gen-module-diagrams.py — 请勿手改 -->\n")
            f.write("```mermaid\n" + txt + "\n```\n")
        cnt = {k: len(v) for k, v in sorted(layers.items())}
        print("%-24s db=%-15s feign=%-3d %s" % (svc, db or "-", len(feign), cnt))


if __name__ == "__main__":
    main()
