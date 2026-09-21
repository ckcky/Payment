#!/usr/bin/env bash
# =============================================================================
# deployment/schema-lint.sh —— schema 静态 lint（spec 033 §6.4 L-1~L-3 / ADR-0081 决策 2）
#
# 三条构建期断言（秒级，CI schema.yml 与本地共用）：
#   L-1  deployment/schema/*.sql 不得出现 `ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS`
#        （MariaDB 方言，MySQL 8 语法错——016 教训；存量库升级路径直接失败）
#   L-2  initdb/01-create-databases.sql 的库集合 == deployment/schema/*.sql 中 CREATE DATABASE
#        的库集合（扣除「遗留豁免清单」——H-033-3：已退役的 refund 保留 + 标注遗留，删除另立 chore）
#   L-3  含 ALTER TABLE 的脚本必须含 information_schema 守卫（015/018/019/030/031 确立的守卫模式，
#        升格为机器门禁——spec 033 §6.2 条文 1/2 的可判定形态）
#
# 用法：bash deployment/schema-lint.sh   （exit 0 全绿 / exit 1 有违规并逐条打印）
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA_DIR="$HERE/schema"
INITDB_FILE="$HERE/initdb/01-create-databases.sql"

# 遗留库豁免清单（H-033-3 裁决：保留 + 标注遗留；只允许 initdb 单侧出现的库名出现在这里）
LEGACY_DBS="refund"

violations=0

note() { printf '%s\n' "$*"; }
bad()  { printf '%s\n' "  ✗ $*"; violations=$((violations + 1)); }

# 去掉 '--' 注释行后输出（lint 一律针对可执行语句，注释里的反例引用不误伤）
strip_comments() {
  grep -v '^[[:space:]]*--' "$1" || true
}

# 从输入里抽取 CREATE DATABASE 的库名（反引号/裸名都支持），小写去重
extract_dbs() {
  awk '{
    line = tolower($0)
    if (line ~ /create[ \t]+database/) {
      name = ""
      if (match(line, /`[a-z0-9_]+`/)) {
        name = substr(line, RSTART + 1, RLENGTH - 2)
      } else if (match(line, /database[ \t]+(if[ \t]+not[ \t]+exists[ \t]+)?[a-z0-9_]+/)) {
        n = substr(line, RSTART, RLENGTH)
        sub(/.*exists[ \t]+/, "", n)
        sub(/database[ \t]+(if[ \t]+not[ \t]+exists[ \t]+)?/, "", n)
        name = n
      }
      if (name != "") print name
    }
  }' | sort -u
}

in_legacy_list() {
  local db="$1"
  for legacy in $LEGACY_DBS; do
    [ "$db" = "$legacy" ] && return 0
  done
  return 1
}

note "== schema lint（L-1 方言 / L-2 库集合一致 / L-3 守卫齐全）=="

# ---- L-1：禁 MariaDB 方言 ----------------------------------------------------
l1_fail=0
for f in "$SCHEMA_DIR"/*.sql; do
  hits=$(strip_comments "$f" | grep -inE 'ADD[[:space:]]+(COLUMN|INDEX)[[:space:]]+IF[[:space:]]+NOT[[:space:]]+EXISTS' || true)
  if [ -n "$hits" ]; then
    while IFS= read -r hit; do
      [ -n "$hit" ] || continue
      bad "L-1 $(basename "$f"):$hit —— ADD COLUMN/INDEX IF NOT EXISTS 是 MariaDB 方言（016 教训），改用 information_schema 守卫"
      l1_fail=1
    done <<< "$hits"
  fi
done
[ "$l1_fail" = "0" ] && note "L-1 OK：无 MariaDB 方言残留"

# ---- L-2：initdb 库集合 == schema CREATE DATABASE 库集合（扣遗留豁免） -------
initdb_dbs=$(strip_comments "$INITDB_FILE" | extract_dbs)
schema_dbs=$(cat "$SCHEMA_DIR"/*.sql | extract_dbs)

l2_fail=0
for db in $initdb_dbs; do
  if ! in_legacy_list "$db"; then
    echo "$schema_dbs" | grep -qx "$db" || { bad "L-2 initdb 声明的库 \`$db\` 在 deployment/schema 无 CREATE DATABASE（漂移：schema 缺库）"; l2_fail=1; }
  fi
done
for db in $schema_dbs; do
  echo "$initdb_dbs" | grep -qx "$db" || { bad "L-2 schema 声明的库 \`$db\` 不在 initdb/01-create-databases.sql（漂移：initdb 缺库，code-debt #1 同类缺陷）"; l2_fail=1; }
done
[ "$l2_fail" = "0" ] && note "L-2 OK：initdb 与 schema 库集合一致（遗留豁免：${LEGACY_DBS}）"

# ---- L-3：ALTER TABLE 必带 information_schema 守卫 ---------------------------
l3_fail=0
for f in "$SCHEMA_DIR"/*.sql; do
  body=$(strip_comments "$f")
  if echo "$body" | grep -qiE 'ALTER[[:space:]]+TABLE'; then
    if ! echo "$body" | grep -qi 'information_schema'; then
      bad "L-3 $(basename "$f") 含 ALTER TABLE 但无 information_schema 守卫——存量库重放不幂等（spec 033 §6.2 条文 1/2）"
      l3_fail=1
    fi
  fi
done
[ "$l3_fail" = "0" ] && note "L-3 OK：所有 ALTER 脚本均带 information_schema 守卫"

if [ "$violations" -gt 0 ]; then
  note "schema lint：$violations 项违规（见上）"
  exit 1
fi
note "schema lint：全部通过"
exit 0
