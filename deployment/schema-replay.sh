#!/usr/bin/env bash
# =============================================================================
# deployment/schema-replay.sh —— schema 双路径可重放门禁（spec 033 §6.3 / ADR-0081 决策 2）
#
# 输入：一个**空** MySQL 8.0 服务端（CI 用 service container；本地可 docker run 临时实例）。
# 用法：bash deployment/schema-replay.sh <host> <port> <user> <password>
#
# 路径 A（空库全量）：initdb → 全量（[0-9][0-9]-*.sql + 027-*）→ 增量（其余 NN-*.sql）
#                     → 结构快照 S_A；**连跑第二遍** → S_A'（幂等自检，spec §6.3）
# 路径 B（存量演进）：drop 全部用户库 → 恢复 baseline/033.sql（上一里程碑基线，spec §6.3）
#                     → 再放全量 + 增量 → 结构快照 S_B
# 门禁：diff(S_A, S_A') 必须为空（幂等）；diff(S_A, S_B) 必须为空（两路径结果一致）。
#       快照 = information_schema 的 COLUMNS + STATISTICS 确定性排序（不含自增值等噪声）。
# 新增 Feature 时的基线递增：该 Feature 的增量被全量吸收后，以路径 A 产物重生成
# baseline/<NNN>.sql（结构 dump）——见 spec 033 §6.3「一次性动作」。
# =============================================================================
set -euo pipefail

HOST_ARG="${1:?usage: schema-replay.sh <host> <port> <user> <password>}"
PORT_ARG="${2:?}"
USER_ARG="${3:-root}"
PASS_ARG="${4:?}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA_DIR="$HERE/schema"
BASELINE_FILE="$SCHEMA_DIR/baseline/033.sql"
WORKDIR="$(mktemp -d /tmp/schema-replay.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

client() {
  MYSQL_PWD="$PASS_ARG" mysql --protocol=tcp -h "$HOST_ARG" -P "$PORT_ARG" -u "$USER_ARG" \
    --default-character-set=utf8mb4 "$@"
}

SYSTEM_DBS="'mysql','information_schema','performance_schema','sys'"

# ---- 基础动作 ---------------------------------------------------------------

apply_sql() { note "  applying $(basename "$1")"; client < "$1" >/dev/null; }

wait_mysql() {
  for _ in $(seq 1 60); do
    if MYSQL_PWD="$PASS_ARG" mysqladmin --protocol=tcp -h "$HOST_ARG" -P "$PORT_ARG" -u "$USER_ARG" ping --silent 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "MySQL 未就绪：$HOST_ARG:$PORT_ARG" >&2
  exit 1
}

drop_user_databases() {
  local dbs
  dbs=$(client -N -B -e "SELECT schema_name FROM information_schema.schemata
         WHERE schema_name NOT IN ($SYSTEM_DBS)")
  for db in $dbs; do
    client -e "DROP DATABASE \`$db\`"
  done
}

# 结构快照：列 + 索引（确定性排序；不含 AUTO_INCREMENT 计数等运行态噪声）
snapshot() {
  client -N -B -e "
    SELECT CONCAT('C|', table_schema, '|', table_name, '|', ordinal_position, '|', column_name,
                  '|', column_type, '|', is_nullable, '|', coalesce(column_default, '~NULL~'), '|', coalesce(extra, ''))
    FROM information_schema.columns
    WHERE table_schema NOT IN ($SYSTEM_DBS)
    ORDER BY table_schema, table_name, ordinal_position;
    SELECT CONCAT('I|', table_schema, '|', table_name, '|', index_name, '|', non_unique, '|',
                  seq_in_index, '|', column_name, '|', index_type)
    FROM information_schema.statistics
    WHERE table_schema NOT IN ($SYSTEM_DBS)
    ORDER BY table_schema, table_name, index_name, seq_in_index;"
}

# 两段式重放：先全量（表先存在），再增量（守卫在全新库上天然 no-op；存量库上做真实演进）
replay_full_and_increments() {
  note "  pass 1/2 全量（[0-9][0-9]-*.sql + 027-*）"
  for f in "$SCHEMA_DIR"/[0-9][0-9]-*.sql "$SCHEMA_DIR"/027-*.sql; do
    [ -e "$f" ] && apply_sql "$f"
  done
  note "  pass 2/2 增量（其余 NN-*.sql，information_schema 守卫）"
  for f in "$SCHEMA_DIR"/*.sql; do
    case "$(basename "$f")" in
      [0-9][0-9]-*.sql|027-*.sql) continue ;;
      *) apply_sql "$f" ;;
    esac
  done
}

note() { printf '%s\n' "$*"; }

# 快照比较：有 diff 用 diff（CI/开发机，输出可读差异表）；无 diff 的极简镜像
# （如 mysql:8.0 容器内自证）退化为 coreutils sort+comm。$1 $2 = 快照，$3 = 差异输出。
compare_snapshots() {
  if command -v diff >/dev/null 2>&1; then
    diff -u "$1" "$2" > "$3" && return 0 || return 1
  fi
  sort "$1" > "$WORKDIR/.cmp_a"
  sort "$2" > "$WORKDIR/.cmp_b"
  if [ "$(cat "$WORKDIR/.cmp_a")" = "$(cat "$WORKDIR/.cmp_b")" ]; then
    : > "$3"
    return 0
  fi
  {
    echo "--- 不一致项（左=本应有/右=实际有，快照已排序）---"
    comm -3 "$WORKDIR/.cmp_a" "$WORKDIR/.cmp_b"
  } > "$3"
  return 1
}

# ---- 门禁执行 ----------------------------------------------------------------

wait_mysql
note "== 路径 A（空库全量，第 1 遍）=="
client -e "DROP DATABASE IF EXISTS \`__replay_probe__\`" >/dev/null
replay_full_and_increments
snapshot > "$WORKDIR/S_A"

note "== 路径 A 连跑第 2 遍（幂等自检）=="
replay_full_and_increments
snapshot > "$WORKDIR/S_A2"

if ! compare_snapshots "$WORKDIR/S_A" "$WORKDIR/S_A2" "$WORKDIR/idempotency.diff"; then
  echo "FAIL：路径 A 连跑两遍快照不一致——存在非幂等脚本（幂等自检红）" >&2
  cat "$WORKDIR/idempotency.diff" >&2
  exit 1
fi
note "  S_A == S_A'（幂等自检通过）"

note "== 路径 B（存量基线 → 全量+增量演进）=="
drop_user_databases
if [ ! -f "$BASELINE_FILE" ]; then
  echo "FAIL：基线缺失 $BASELINE_FILE（spec 033 §6.3：首个基线 033.sql 已入库，不得删除）" >&2
  exit 1
fi
apply_sql "$BASELINE_FILE"
replay_full_and_increments
snapshot > "$WORKDIR/S_B"

gate_failed=0
if ! compare_snapshots "$WORKDIR/S_A" "$WORKDIR/S_B" "$WORKDIR/paths.diff"; then
  echo "FAIL：路径 A（空库全量）与路径 B（基线+增量）快照不一致——" >&2
  echo "     全量文件描述的『目标形状』与增量脚本描述的『演进结果』存在分歧（spec 033 §6.1 D-A/D-B）" >&2
  cat "$WORKDIR/paths.diff" >&2
  gate_failed=1
else
  note "  S_A == S_B（双路径一致）"
fi

note "== 快照规模：$(wc -l < "$WORKDIR/S_A" | tr -d ' ') 行结构事实（列+索引）=="
if [ "$gate_failed" = "1" ]; then
  exit 1
fi
note "schema-replay：双路径门禁全部通过"
