#!/usr/bin/env bash
# demo/mq-group-entries.sh —— 读取指定消费组已消费条数（entries-read）
#
# 用法：bash mq-group-entries.sh <topic> <group>
# 输出：整数（条目数）；组不存在或 Redis 不可达时输出 -1。
#
# 背景：redis-cli 经管道（非 TTY）时 XINFO GROUPS 输出为「一行一个 token」：
#   name\n<catalog>\nconsumers\n1\n...\nentries-read\n10\nlag\n0\n
# 键与其值分行，故需「上一行是键」的状态机取值，不能直接取 $2。
set -uo pipefail

TOPIC="${1:?topic required}"
GROUP="${2:?group required}"
REDIS_CONTAINER="${REDIS_CONTAINER:-payment-redis}"

docker exec "$REDIS_CONTAINER" redis-cli XINFO GROUPS "mq:stream:$TOPIC" 2>/dev/null | awk -v g="$GROUP" '
  prev=="name" {cur=$1}
  cur==g && prev=="entries-read" {print $1; found=1}
  {prev=$1}
  END {if (!found) print "-1"}'
