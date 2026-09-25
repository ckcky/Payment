#!/usr/bin/env bash
# 容器模式一键启动（spec 026 / ADR-0070）
#
# 10 个应用服务 + 7 个中间件全部容器化。与宿主的 `start-all.sh` **互斥**（端口 8081–8091 共用）。
#
# 用法：
#   bash deployment/start-container.sh              # 构建（如缺镜像）+ 启动 + 等待健康
#   PAYMENT_SKIP_BUILD=1 bash deployment/start-container.sh   # 跳过 jar 构建（jar 已就绪时）
#   PAYMENT_CHANNEL_MOCK_SCENARIO=BUSINESS_UNKNOWN bash deployment/start-container.sh
#
# 停止：
#   bash deployment/stop-all.sh
#
# 与宿主模式的关键差异（ADR-0070）：
#   - 不依赖宿主 JDK / Maven 版本（镜像自带 JRE 21），消除 java 11 vs RunMojo 需 17+ 这类问题；
#   - 进程生命周期由 Docker 托管，不会随终端/任务结束被回收；
#   - 应用配置地址由 compose environment 注入（不修改任何 application.yml）。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="deployment/docker-compose.yml"
JAR_DIR="deployment/output/jars"

# ---- 0. 前置：docker 可用 ----
if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker 命令。请先安装并启动 Docker Desktop。" >&2
  echo "  验证：docker --version" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "✗ Docker 守护进程未运行。请先启动 Docker Desktop（macOS 可 open -a Docker）。" >&2
  exit 1
fi

# ---- 1. 前置：模式守卫（不要在宿主模式运行时抢占端口）----
# shellcheck source=lib-mode-guard.sh
source "$ROOT_DIR/deployment/lib-mode-guard.sh"
guard_no_host_apps "start-container.sh" || exit 1

# ---- 1b. 前置：沙箱渠道的启动期强校验（spec 030 / FR-134 + FR-103）----
# 与宿主模式 start-all.sh 同口径：配错不许静默走默认（ADR-0049 第 2 条）。
# compose 只做透传，缺失时容器会 FAIL FAST，但那时报错埋在容器日志里；此处提前中止更好排查。
if [ "${PAYMENT_ALIPAY_SANDBOX_ENABLED:-false}" = "true" ]; then
  missing_sandbox=""
  for v in PAYMENT_ALIPAY_SANDBOX_APP_ID PAYMENT_ALIPAY_SANDBOX_APP_PRIVATE_KEY PAYMENT_ALIPAY_SANDBOX_ALIPAY_PUBLIC_KEY; do
    if [ -z "${!v:-}" ]; then missing_sandbox="$missing_sandbox $v"; fi
  done
  if [ -n "$missing_sandbox" ]; then
    echo "✗ PAYMENT_ALIPAY_SANDBOX_ENABLED=true 但缺少必需变量：$missing_sandbox" >&2
    echo "  沙箱密钥一律 env 注入（FR-290/INV-2）；缺失时 payment-service 亦会启动失败。" >&2
    exit 1
  fi
  if [ -z "${PAYMENT_CHANNEL_NOTIFY_URL:-}" ]; then
    echo "✗ PAYMENT_ALIPAY_SANDBOX_ENABLED=true 但未设置 PAYMENT_CHANNEL_NOTIFY_URL。" >&2
    echo "  notify 是资金事实的唯一权威来源（FR-103 / Q5）；缺失时沙箱下单必然 400 INVALID_ARGUMENT。" >&2
    echo "  本地演示请先用内网穿透拿到公网域名（8084 已 publish 到宿主，穿透指向宿主 8084 即可）：" >&2
    echo "      ngrok http 8084" >&2
    echo "      export PAYMENT_CHANNEL_NOTIFY_URL=https://<ngrok-域名>/internal/channels/ALIPAY/callback" >&2
    exit 1
  fi
  echo "    已开启支付宝沙箱渠道（容器模式，gateway=${PAYMENT_ALIPAY_SANDBOX_GATEWAY_URL:-默认沙箱网关}）"
  echo "    回调地址（须为支付宝侧可访问的公网地址）：${PAYMENT_CHANNEL_NOTIFY_URL}"
else
  echo "    支付宝沙箱渠道未开启（PAYMENT_ALIPAY_SANDBOX_ENABLED=${PAYMENT_ALIPAY_SANDBOX_ENABLED:-false}）—— 演示走本地 mock"
fi

SERVICES=(
  merchant-service catalog-service order-service payment-service
  fulfillment-service entitlement-service reconciliation-service settlement-service
  ledger-service mock-channel-web
)

# ---- 2. 构建 jar（可选）----
if [ "${PAYMENT_SKIP_BUILD:-0}" = "1" ]; then
  echo "==> [1/4] PAYMENT_SKIP_BUILD=1，跳过 jar 构建"
else
  echo "==> [1/4] 宿主构建 fat jar（首次较慢；PAYMENT_SKIP_BUILD=1 可跳过）"
  # 与 start-all.sh 一致：必须 clean，避免残留半成品 class 引发运行期 NoClassDefFoundError
  "${MAVEN_BIN:-./mvnw}" ${MAVEN_ARGS:-} -q clean install -DskipTests
fi

# ---- 3. fat jar 齐全性检查 ----
# 这是 ADR-0070 D3 的代价：镜像只 COPY，故 jar 必须先由宿主构建。
# 放在**构建之后、镜像构建之前**：新建克隆无 jar 时先自动构建（而不是直接报错拒跑），
# 构建失败/被跳过导致 jar 仍缺失时，在这里给出明确指引——而不是让 compose build 在
# COPY 阶段才失败，那会留下「镜像构建半途而废」的难排查现场。
MISSING=()
for svc in "${SERVICES[@]}"; do
  [ -f "$JAR_DIR/$svc-0.1.0-SNAPSHOT.jar" ] || MISSING+=("$svc")
done
if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "✗ 缺少 ${#MISSING[@]} 个 fat jar：${MISSING[*]}" >&2
  echo "" >&2
  echo "  容器镜像只 COPY 产物（ADR-0070 D3），请先在**宿主**构建：" >&2
  echo "      ./mvnw clean install -DskipTests" >&2
  echo "  或一步到位：" >&2
  echo "      bash deployment/build-images.sh" >&2
  exit 1
fi

# ---- 4. 构建镜像并启动 ----
echo "==> [2/4] 构建应用镜像（10 个，复用 deployment/docker/Dockerfile）"
docker compose -f "$COMPOSE_FILE" --profile full build

echo "==> [3/4] 启动全栈（7 中间件 + 10 应用）"
docker compose -f "$COMPOSE_FILE" --profile full up -d

echo "==> [4/4] 等待 10 个服务健康就绪（最多 240s）"
# 任一服务未就绪即 exit 1（与 start-all.sh 的 Nacos 就绪策略一致）：
# 假成功比失败难排查一个数量级。
ALL_READY=0
for i in $(seq 1 120); do
  UP=0
  for p in 8081 8082 8083 8084 8086 8087 8088 8089 8090 8091; do
    code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -m 2 "http://localhost:$p/actuator/health" 2>/dev/null || echo 000)"
    [ "$code" = "200" ] && UP=$((UP + 1))
  done
  if [ "$UP" -eq 10 ]; then
    ALL_READY=1
    echo "    全部 10 个服务已就绪（约 $((i * 2))s）"
    break
  fi
  sleep 2
done

if [ "$ALL_READY" != "1" ]; then
  echo "✗ 240s 内未全部就绪（当前 $UP/10）。排查：" >&2
  echo "    docker compose -f $COMPOSE_FILE --profile full ps" >&2
  echo "    docker compose -f $COMPOSE_FILE --profile full logs --tail=80 <service>" >&2
  exit 1
fi

echo ""
echo "=================================================="
echo "  容器模式启动完成。入口："
echo "    演示控制台   http://localhost:8091/demo"
echo "    Swagger      http://localhost:8084/swagger-ui.html"
echo "    Grafana      http://localhost:3000   （admin/admin）"
echo "    Prometheus   http://localhost:9090"
echo "    Nacos        http://localhost:8848/nacos"
echo "=================================================="
echo "  查看日志：docker compose -f $COMPOSE_FILE logs -f payment-service"
echo "  停止全部：bash deployment/stop-all.sh"
echo "  切回宿主模式：bash deployment/stop-all.sh && bash deployment/start-all.sh"
