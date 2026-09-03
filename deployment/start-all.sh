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

echo "==> [1/3] 启动 MySQL + Prometheus + Grafana + Nacos（容器）"
docker compose -f deployment/docker-compose.yml up -d

echo "==> [1b] 等待 Nacos (8848) 就绪（ADR-0059：注册中心）..."
for i in $(seq 1 45); do
  if curl -fsS -o /dev/null "http://127.0.0.1:8848/nacos/actuator/health"; then
    echo "    Nacos 已就绪"
    break
  fi
  sleep 2
done

echo "==> [2/3] 构建并安装本地依赖（首次较慢，后续可跳过）"
./mvnw -q install -DskipTests

echo "==> [3/3] 后台启动 10 个进程：9 服务 + mock-channel-web（演示组件，ADR-0048 修订版）"
SERVICES=(
  merchant-service catalog-service order-service payment-service refund-service
  fulfillment-service entitlement-service reconciliation-service settlement-service
  ledger-service
)
for svc in "${SERVICES[@]}"; do
  nohup ./mvnw -pl "$svc" spring-boot:run > "$LOG_DIR/$svc.log" 2>&1 &
  echo "$! $svc" >> "$PID_FILE"
  echo "    $svc  (PID $!)"
done

# 演示前置（ADR-0025 占位）：渠道回调验签本期为空实现（回调一律放行），故 payment-service
#   不再消费 PAYMENT_CHANNEL_SECRET。下方变量仅作为 mock-channel-web 签名演示的同源密钥保留，
#   供将来接入真实验签（ADR-0052，见 docs/adr/0013，当前 ⛔ Not Implemented）时两侧对齐使用。
if [ -z "${PAYMENT_CHANNEL_SECRET:-}" ]; then
  export PAYMENT_CHANNEL_SECRET="demo-channel-secret-2026"
  echo "    已设置演示密钥 PAYMENT_CHANNEL_SECRET（payment 当前忽略；mock-channel-web 用于签名演示）"
fi
# 演示前置：/payments/{id}/resolve 人工收敛端点的管理令牌（UNKNOWN 权威裁定演示用）
if [ -z "${PAYMENT_ADMIN_TOKEN:-}" ]; then
  export PAYMENT_ADMIN_TOKEN="demo-admin-token"
  echo "    已设置默认演示令牌 PAYMENT_ADMIN_TOKEN（可用环境变量覆盖）"
fi
# mock-cashier 开启：支付创建走"收银台跳转"路径（payUrl），默认关闭不影响既有行为
export PAYMENT_MOCK_CASHIER_ENABLED="${PAYMENT_MOCK_CASHIER_ENABLED:-true}"

nohup ./mvnw -pl mock-channel-web spring-boot:run > "$LOG_DIR/mock-channel-web.log" 2>&1 &
echo "$! mock-channel-web" >> "$PID_FILE"
echo "    mock-channel-web  (PID $!)"

echo ""
echo "=================================================="
echo "  启动完成。入口："
echo "    演示控制台   http://localhost:8091/demo            （下单 → 收银台 → 轮询状态）"
echo "    Swagger      http://localhost:8084/swagger-ui.html   （其它服务端口 8081~8089 同理）"
echo "    Grafana      http://localhost:3000   （admin/admin，内置「PaymentArch 业务指标」看板）"
echo "    Prometheus   http://localhost:9090"
echo "    MySQL        localhost:3306  （root/root）"
echo "=================================================="
echo "  实时日志：tail -f deployment/logs/payment-service.log"
echo "  停止全部：bash deployment/stop-all.sh"
