#!/usr/bin/env bash
#
# stop.sh —— 停止发布包全部进程：10 个 JVM + 基础设施容器（保留 MySQL 数据卷）
#
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/deployment/logs"
PID_FILE="$LOG_DIR/.pids"

echo "==> 停止 JVM 进程"
if [ -f "$PID_FILE" ]; then
  while read -r pid svc; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null && echo "    stopped $svc (PID $pid)" || true
  done < "$PID_FILE"
  rm -f "$PID_FILE"
else
  echo "    没有 PID 文件（deployment/logs/.pids），跳过。"
fi

echo "==> 停止容器（保留数据卷）"
docker compose -f deployment/docker-compose.yml down

echo ""
echo "完成。重新启动：bash start.sh"
