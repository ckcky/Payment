#!/usr/bin/env bash
# 停止全部（双模感知，spec 026 / ADR-0070 D2）
#
# 两种模式都停，无需用户判断当前在哪种模式：
#   ① 宿主模式的 10 个应用进程（按 .pids 记录，SIGTERM 优雅停机）
#   ② 容器模式的应用容器 + 7 个中间件容器（保留 MySQL 数据卷）
#
# 停机语义（spec 023 / M5）：kill 发送 SIGTERM → 各服务 graceful shutdown
# （application.yml: server.shutdown=graceful + drain 上限 30s）——在途请求
# （支付/退款回调等）等待完成后才关闭端口，期间不接受新请求。
# 容器侧同样受益：compose down 默认先 SIGTERM，超过 stop_grace_period 才 SIGKILL。
#
# 用法：bash deployment/stop-all.sh

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/deployment/logs"
PID_FILE="$LOG_DIR/.pids"
COMPOSE_FILE="deployment/docker-compose.yml"

echo "==> 停止宿主模式应用进程（若有）"
if [ -f "$PID_FILE" ]; then
  while read -r pid svc; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null && echo "    stopped $svc (PID $pid)" || true
  done < "$PID_FILE"
  rm -f "$PID_FILE"
else
  echo "    没有 PID 文件（.pids），跳过。"
fi

echo "==> 停止容器（应用容器 + 中间件，保留数据卷）"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  # 带 --profile full 才能把应用容器一并停掉；不带 profile 时 compose 会忽略带 profile 的服务，
  # 导致应用容器残留、端口仍被占用（正是双模守卫要防的情形）。
  docker compose -f "$COMPOSE_FILE" --profile full down
else
  echo "    docker 不可用或守护进程未运行，跳过容器停止。"
fi

echo ""
echo "完成。重新启动："
echo "    容器模式：bash deployment/start-container.sh"
echo "    宿主模式：bash deployment/start-all.sh"
echo "提示：若个别 java 进程残留（Windows 上 kill 可能留下 fork 的子进程），可执行 taskkill //F //IM java.exe 兜底。"
