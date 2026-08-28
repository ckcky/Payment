#!/usr/bin/env bash
set -uo pipefail

# 停止全部：9 个微服务（宿主进程） + 容器（保留 MySQL 数据卷）
# 用法：bash deployment/stop-all.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/deployment/logs"
PID_FILE="$LOG_DIR/.pids"

echo "==> 停止微服务"
if [ -f "$PID_FILE" ]; then
  while read -r pid svc; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null && echo "    stopped $svc (PID $pid)" || true
  done < "$PID_FILE"
  rm -f "$PID_FILE"
else
  echo "    没有 PID 文件（.pids），跳过。"
fi

echo "==> 停止容器（保留数据卷）"
docker compose -f deployment/docker-compose.yml down

echo ""
echo "完成。重新启动：bash deployment/start-all.sh"
echo "提示：若个别 java 进程残留（Windows 上 kill 可能留下 fork 的子进程），可执行 taskkill //F //IM java.exe 兜底。"
