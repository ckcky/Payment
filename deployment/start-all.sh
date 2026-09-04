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

# JVM 内存上限（默认开启，可用环境变量覆盖/禁用）：
#   spring-boot:run 让每个服务占 2 个 JVM（maven 启动器 + fork 出的应用进程），
#   11 个进程共 22 个 JVM；不限 -Xmx 时在小内存机器上会把内存 commit 额度耗尽
#   （2026-09-04 实测 12.7GB 物理内存机器 commit 打满 52GB/52.7GB，新进程 malloc 失败、
#   秒杀 p95 由毫秒级劣化到 600ms+）。覆盖方式：
#     JAVA_TOOL_OPTIONS="-Xmx512m" bash deployment/start-all.sh   # 调大
#     JAVA_TOOL_OPTIONS="" bash deployment/start-all.sh           # 关闭上限
if [ -z "${JAVA_TOOL_OPTIONS:-}" ]; then
  export JAVA_TOOL_OPTIONS="-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m"
  echo "    已设置默认 JVM 内存上限 JAVA_TOOL_OPTIONS='-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m'（可用环境变量覆盖）"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker 命令。请先安装并启动 Docker Desktop（Windows/macOS）或 docker 引擎（Linux）。"
  echo "  验证：docker --version"
  exit 1
fi

echo "==> [1/3] 启动 MySQL + Prometheus + Grafana + Nacos（容器）"
docker compose -f deployment/docker-compose.yml up -d

echo "==> [1b] 等待 Nacos (8848) 就绪（ADR-0059：注册中心）..."
# 注意：Nacos 3.x 已移除 /nacos/actuator/health（返回 404），2.x 的就绪探针不再适用。
# 改用 naming 模块的 operator/metrics，就绪时返回 {"status":"UP"}。
for i in $(seq 1 45); do
  if curl -fsS "http://127.0.0.1:8848/nacos/v1/ns/operator/metrics" 2>/dev/null | grep -q UP; then
    echo "    Nacos 已就绪"
    break
  fi
  sleep 2
done

echo "==> [2/3] 全量 clean 构建（首次较慢；PAYMENT_SKIP_BUILD=1 可跳过）"
# 必须 clean：target 里可能残留 VS Code JDT 写入的半成品 class（外层类在、内部类缺失），
# spring-boot:run 直接复用会导致运行期 NoClassDefFoundError 且被 JVM 缓存、重启前永久 500
# （2026-09-04 实跑踩坑：order-service RateLimiter$Window 缺失，所有 /orders 500）。
if [ "${PAYMENT_SKIP_BUILD:-0}" = "1" ]; then
  echo "    PAYMENT_SKIP_BUILD=1，跳过构建"
else
  # MAVEN_BIN：无 POSIX 路径转换的 bash（如沙箱）里 ./mvnw(sh) 会把 /c/... 传给 java
  # 导致 Launcher ClassNotFoundException；此时用 MAVEN_BIN=.../bin/mvn.cmd 覆盖。
  "${MAVEN_BIN:-./mvnw}" ${MAVEN_ARGS:-} -q clean install -DskipTests
fi

echo "==> [3/3] 后台启动 10 个进程：9 服务 + mock-channel-web（演示组件，ADR-0048 修订版）"

# 演示前置（ADR-0025 占位）：渠道回调验签本期为空实现（回调一律放行），故 payment-service
#   不再消费 PAYMENT_CHANNEL_SECRET。下方变量仅作为 mock-channel-web 签名演示的同源密钥保留，
#   供将来接入真实验签（ADR-0052，见 docs/adr/0013，当前 ⛔ Not Implemented）时两侧对齐使用。
# 注意：这些 export 必须在服务启动【之前】——否则 payment-service 读不到
#   PAYMENT_MOCK_CASHIER_ENABLED，收银台路径（payUrl）静默退化为内联扣款
#   （2026-09-04 实跑踩坑：下单响应永远 SUCCEEDED、回调链路演不出来）。
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

SERVICES=(
  merchant-service catalog-service order-service payment-service
  fulfillment-service entitlement-service reconciliation-service settlement-service
  ledger-service
)
for svc in "${SERVICES[@]}"; do
  nohup "${MAVEN_BIN:-./mvnw}" ${MAVEN_ARGS:-} -pl "$svc" spring-boot:run > "$LOG_DIR/$svc.log" 2>&1 &
  echo "$! $svc" >> "$PID_FILE"
  echo "    $svc  (PID $!)"
done

# 注意：mock-channel-web 位于 deployment/ 下（ADR-0048 修订版），-pl 必须写模块相对仓库根的路径，
# 直接写 artifactId 会被 Maven 当作不存在的目录而报 "Could not find the selected project in the reactor"。
nohup "${MAVEN_BIN:-./mvnw}" ${MAVEN_ARGS:-} -pl deployment/mock-channel-web spring-boot:run > "$LOG_DIR/mock-channel-web.log" 2>&1 &
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
