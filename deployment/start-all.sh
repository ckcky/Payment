#!/usr/bin/env bash
set -euo pipefail

# 一键启动：MySQL / Prometheus / Grafana（容器） + 9 个微服务（宿主进程）
# 用法（Windows 在 Git Bash 里跑，macOS/Linux 直接跑）：
#   bash deployment/start-all.sh
# 停止：
#   bash deployment/stop-all.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/deployment/logs"
PID_FILE="$LOG_DIR/.pids"
mkdir -p "$LOG_DIR"
: > "$PID_FILE"

if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker 命令。请先安装并启动 Docker Desktop（Windows/macOS）或 docker 引擎（Linux）。"
  echo "  验证：docker --version"
  exit 1
fi

echo "==> [1/3] 启动 MySQL + Prometheus + Grafana（容器）"
docker compose -f deployment/docker-compose.yml up -d

echo "==> [2/3] 构建并安装本地依赖（首次较慢，后续可跳过）"
./mvnw -q install -DskipTests

echo "==> [3/3] 后台启动 9 个微服务（日志 → deployment/logs/）"
SERVICES=(
  merchant-service catalog-service order-service payment-service refund-service
  fulfillment-service entitlement-service reconciliation-service settlement-service
)
for svc in "${SERVICES[@]}"; do
  nohup ./mvnw -pl "$svc" spring-boot:run > "$LOG_DIR/$svc.log" 2>&1 &
  echo "$! $svc" >> "$PID_FILE"
  echo "    $svc  (PID $!)"
done

echo ""
echo "=================================================="
echo "  启动完成。入口："
echo "    Swagger      http://localhost:8084/swagger-ui.html   （其它服务端口 8081~8089 同理）"
echo "    Grafana      http://localhost:3000   （admin/admin，内置「PaymentArch 业务指标」看板）"
echo "    Prometheus   http://localhost:9090"
echo "    MySQL        localhost:3306  （root/root）"
echo "=================================================="
echo "  实时日志：tail -f deployment/logs/payment-service.log"
echo "  停止全部：bash deployment/stop-all.sh"
