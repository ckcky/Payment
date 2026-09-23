#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 deployment/schema/*.sql 解析真实表结构，生成 Mermaid erDiagram。

用途：系统设计文档 §4 领域模型的 ER 图必须来自真实 schema，禁止手写编造。
用法：
    python deployment/tools/gen-er-diagrams.py            # 生成到 deployment/output/er/
    python deployment/tools/gen-er-diagrams.py --stdout order   # 只打印某个库

解析规则：
- `USE xxx;` 切换当前库；`CREATE DATABASE` 同效
- `CREATE TABLE [IF NOT EXISTS] name ( ... )`  → 表 + 列 + PK/UK/KEY/FK
- `ALTER TABLE name ADD COLUMN / ADD CONSTRAINT ... FOREIGN KEY / ADD UNIQUE KEY` → 增量合并
- 隐式关系：字段名 `<x>_id` 指向同库 `<x>` 或 `<xs>` 或 `<xes>` 表
"""

import os
import re
import sys
import argparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCHEMA_DIR = os.path.join(ROOT, "deployment", "schema")
OUT_DIR = os.path.join(ROOT, "deployment", "output", "er")

# 需要保留的 MySQL 类型 → Mermaid 可用标识
TYPE_MAP = [
    (r"^bigint", "bigint"),
    (r"^int\b", "int"),
    (r"^tinyint", "tinyint"),
    (r"^smallint", "smallint"),
    (r"^varchar", "varchar"),
    (r"^char\b", "char"),
    (r"^text", "text"),
    (r"^mediumtext", "text"),
    (r"^longtext", "text"),
    (r"^datetime", "datetime"),
    (r"^timestamp", "datetime"),
    (r"^date\b", "date"),
    (r"^decimal", "decimal"),
    (r"^numeric", "decimal"),
    (r"^boolean", "boolean"),
    (r"^bool\b", "boolean"),
    (r"^json", "json"),
]

USE_RE = re.compile(r"^\s*USE\s+`?(\w+)`?\s*;", re.I | re.M)
CREATEDB_RE = re.compile(r"CREATE\s+DATABASE[^`\w]*`?(\w+)`?", re.I)
CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\((.*?)\)\s*(?:ENGINE|;)",
    re.I | re.S,
)
ALTER_RE = re.compile(r"ALTER\s+TABLE\s+`?(\w+)`?\s+(.*?);", re.I | re.S)
COL_RE = re.compile(r"^\s*`?(\w+)`?\s+([A-Za-z]+(?:\s*\([^)]*\))?)")
PK_RE = re.compile(r"^\s*PRIMARY\s+KEY\s*\(([^)]*)\)", re.I | re.M)
UK_RE = re.compile(r"^\s*(?:UNIQUE\s+KEY|UNIQUE)\s*`?\w*`?\s*\(([^)]*)\)", re.I | re.M)
KEY_RE = re.compile(r"^\s*KEY\s+`?\w*`?\s*\(([^)]*)\)", re.I | re.M)
FK_RE = re.compile(
    r"(?:CONSTRAINT\s+`?\w+`?\s+)?FOREIGN\s+KEY\s*\(([^)]*)\)\s*REFERENCES\s+`?(\w+)`?\s*\(([^)]*)\)",
    re.I,
)


def norm_type(raw):
    t = raw.strip().lower()
    for pat, out in TYPE_MAP:
        if re.match(pat, t):
            return out
    return re.sub(r"[^a-z0-9_]", "", t) or "text"


def split_cols(body):
    """按顶层逗号切分列定义（忽略括号内的逗号）。"""
    parts, depth, buf = [], 0, ""
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    if buf.strip():
        parts.append(buf)
    return [p.strip() for p in parts if p.strip()]


def parse_file(path):
    """返回 [(db, table, cols, pk, uks, keys, fks)]"""
    src = open(path, encoding="utf-8", errors="replace").read()
    # 去掉整行注释
    src = re.sub(r"^\s*--.*$", "", src, flags=re.M)
    out = []
    cur_db = None
    pos = 0
    # 按语句推进，追踪当前库
    for m in re.finditer(r"(USE\s+`?\w+`?\s*;|CREATE\s+DATABASE[^;]*;|CREATE\s+TABLE[\s\S]*?\)\s*(?:ENGINE[^;]*)?;|ALTER\s+TABLE[\s\S]*?;)", src, re.I):
        stmt = m.group(0)
        mu = re.match(r"\s*USE\s+`?(\w+)`?\s*;", stmt, re.I)
        mc = re.match(r"\s*CREATE\s+DATABASE(?:[^`\w])*?`?(\w+)`?", stmt, re.I)
        if mu:
            cur_db = mu.group(1)
            continue
        if mc:
            cur_db = mc.group(1)
            continue
        mt = re.match(r"\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(([\s\S]*)\)", stmt, re.I)
        if mt:
            name, body = mt.group(1), mt.group(2)
            cols, pk, uks, keys, fks = [], set(), set(), set(), []
            for part in split_cols(body):
                if PK_RE.match(part):
                    for c in PK_RE.match(part).group(1).split(","):
                        pk.add(c.strip().strip("`"))
                    continue
                mk = UK_RE.match(part)
                if mk:
                    uks.add(mk.group(1).split(",")[0].strip().strip("`"))
                    continue
                mk2 = KEY_RE.match(part)
                if mk2:
                    keys.add(mk2.group(1).split(",")[0].strip().strip("`"))
                    continue
                mf = FK_RE.search(part)
                if mf and "FOREIGN KEY" in part.upper():
                    fks.append((mf.group(1).strip().strip("`"), mf.group(2), mf.group(3).strip().strip("`")))
                    continue
                mc2 = COL_RE.match(part)
                if mc2 and not re.match(r"^\s*(PRIMARY|UNIQUE|KEY|FOREIGN|CONSTRAINT|CHECK|INDEX)\b", part, re.I):
                    cols.append((mc2.group(1), norm_type(mc2.group(2))))
            out.append((cur_db, name, cols, pk, uks, keys, fks, m.start()))
            continue
        ma = re.match(r"\s*ALTER\s+TABLE\s+`?(\w+)`?\s+([\s\S]*?);\s*$", stmt, re.I)
        if ma:
            name, tail = ma.group(1), ma.group(2)
            cols, pk, uks, keys, fks = [], set(), set(), set(), []
            for part in split_cols(tail):
                up = part.upper()
                if "FOREIGN KEY" in up:
                    mf = FK_RE.search(part)
                    if mf:
                        fks.append((mf.group(1).strip().strip("`"), mf.group(2), mf.group(3).strip().strip("`")))
                    continue
                # 先剥掉 ADD / MODIFY / CHANGE 前缀，再判断是约束还是列
                stripped = re.sub(r"^\s*(ADD|MODIFY|CHANGE)\s+(COLUMN\s+)?", "", part, flags=re.I)
                if stripped != part:
                    if re.match(r"^\s*(PRIMARY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN|CHECK)\b", stripped, re.I):
                        pass  # 落到下面的约束分支继续判断
                    else:
                        mc2 = COL_RE.match(stripped)
                        if mc2:
                            cols.append((mc2.group(1), norm_type(mc2.group(2))))
                        continue
                mk = UK_RE.search(part)
                if mk and "UNIQUE" in up:
                    uks.add(mk.group(1).split(",")[0].strip().strip("`"))
                    continue
                mk2 = KEY_RE.search(part)
                if mk2:
                    keys.add(mk2.group(1).split(",")[0].strip().strip("`"))
            if cols or fks or uks or keys:
                out.append((cur_db, name, cols, pk, uks, keys, fks, m.start()))
    return out


def merge(stmts):
    """按 (db, table) 合并，后来的 ALTER 增量追加。"""
    tables = {}
    order = []
    for db, name, cols, pk, uks, keys, fks, pos in stmts:
        if not db:
            continue
        k = (db, name)
        if k not in tables:
            tables[k] = {"cols": [], "pk": set(), "uks": set(), "keys": set(), "fks": []}
            order.append(k)
        t = tables[k]
        seen = {c[0] for c in t["cols"]}
        for c in cols:
            if c[0] not in seen:
                t["cols"].append(c)
                seen.add(c[0])
        t["pk"] |= pk
        t["uks"] |= uks
        t["keys"] |= keys
        t["fks"] += fks
    return tables, order


def guess_parent(col, db_tables):
    """从 `<x>_id`（外键风格）或 `<x>_no`（业务单号引用，ADR-0063）推断父表。

    只认同库表：跨库引用一律不画线（跨服务禁止共享表 / 共享外键）。
    """
    stem = None
    for suffix in ("_id", "_no"):
        if col.endswith(suffix) and len(col) > len(suffix):
            stem = col[: -len(suffix)]
            break
    if not stem:
        return None
    for cand in (stem, stem + "s", stem + "es", stem.replace("_", ""), stem + "_info"):
        if cand in db_tables:
            return cand
    return None


def render_er(db, tables, order):
    db_tables = {t for (d, t) in order if d == db}
    lines = ["erDiagram"]
    rels = []
    for (d, name) in order:
        if d != db:
            continue
        t = tables[(d, name)]
        lines.append("    %s {" % name)
        # PK 排首位，其余保持 schema 原顺序
        ordered = sorted(t["cols"], key=lambda c: (0 if c[0] in t["pk"] else 1))
        for cname, ctype in ordered:
            marks = []
            if cname in t["pk"]:
                marks.append("PK")
            elif cname in t["uks"]:
                marks.append("UK")
            if any(cname == f[0] for f in t["fks"]):
                marks.append("FK")
            line = "        %s %s" % (ctype, cname)
            if marks:
                line += " " + ",".join(marks)
            lines.append(line)
        lines.append("    }")
    # 关系：显式 FK 优先
    emitted = set()
    for (d, name) in order:
        if d != db:
            continue
        t = tables[(d, name)]
        for col, ref, _ in t["fks"]:
            if ref in db_tables:
                key = (ref, name, col)
                if key in emitted:
                    continue
                emitted.add(key)
                one_to_one = col in t["uks"]
                rels.append("    %s ||--%s %s : %s" % (ref, "||" if one_to_one else "o{", name, col))
    # 隐式关系
    for (d, name) in order:
        if d != db:
            continue
        t = tables[(d, name)]
        fk_cols = {f[0] for f in t["fks"]}
        for cname, _ in t["cols"]:
            if cname in fk_cols or cname in t["pk"]:
                continue
            parent = guess_parent(cname, db_tables)
            if not parent or parent == name:
                continue
            key = (parent, name, cname)
            if key in emitted:
                continue
            emitted.add(key)
            one_to_one = cname in t["uks"]
            rels.append(
                '    %s ||--%s %s : "%s（业务单号·无FK）"'
                % (parent, "||" if one_to_one else "o{", name, cname)
            )
    lines += rels
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stdout", help="只打印指定库的 ER 图")
    args = ap.parse_args()

    stmts = []
    for fn in sorted(os.listdir(SCHEMA_DIR)):
        if fn.endswith(".sql"):
            stmts += parse_file(os.path.join(SCHEMA_DIR, fn))
    tables, order = merge(stmts)

    dbs = []
    for (d, _) in order:
        if d not in dbs:
            dbs.append(d)

    os.makedirs(OUT_DIR, exist_ok=True)
    for db in dbs:
        txt = render_er(db, tables, order)
        if args.stdout:
            if db == args.stdout:
                print(txt)
            continue
        with open(os.path.join(OUT_DIR, db + ".md"), "w", encoding="utf-8") as f:
            f.write("<!-- AUTO-GENERATED by deployment/tools/gen-er-diagrams.py — 请勿手改 -->\n")
            f.write("```mermaid\n" + txt + "\n```\n")
        ntab = sum(1 for (d, _) in order if d == db)
        print("%-16s %d 表 %s" % (db, ntab, os.path.join(OUT_DIR, db + ".md")))


if __name__ == "__main__":
    main()
