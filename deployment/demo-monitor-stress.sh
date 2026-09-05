#!/usr/bin/env bash
# demo-monitor-stress.sh —— 一键：起全栈 + 持续发模拟请求 + 打开监控 + 压力测试
#
# 对应需求：把服务都跑起来 → 脚本一直发模拟请求 → 打开监控看指标 → 进行压力测试。
#
# 依赖（本沙箱无 Docker，无法在此执行；请在具备 Docker 的机器上运行）：
#   - Docker / Docker Desktop（MySQL / Redis / Nacos / Prometheus / Grafana 以容器运行）
#   - 本机 JDK17+ / Maven（./mvnw 自动下载） / Node.js（压测脚本）
#
# 用法：
#   bash deployment/demo-monitor-stress.sh
#   SKU_ID=1 SECKILL_TOTAL=1000 bash deployment/demo-monitor-stress.sh
#
# 停止：
#   bash deployment/stop-all.sh
#   kill $(grep continuous-emit deployment/logs/.demo_pids | cut -d' ' -f1)

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
LOG_DIR="$ROOT_DIR/deployment/logs"
mkdir -p "$LOG_DIR"
PID_FILE="$LOG_DIR/.demo_pids"
: > "$PID_FILE"

# 0) 依赖检查
if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker。请先安装并启动 Docker Desktop（Windows/macOS）或 docker 引擎（Linux），再运行本脚本。"
  echo "  验证：docker --version"
  exit 1
fi
if ! command -v node >/dev/null 2>&1; then
  echo "✗ 未找到 node（用于压测脚本）。请安装 Node.js。"
  exit 1
fi

# 1) 起全栈（infra 容器 + 10 服务宿主进程 + mock-channel-web）
echo "==> [1] 启动全栈（infra 容器 + 10 服务 + mock-channel-web）"
bash deployment/start-all.sh

# 2) 等待所有服务健康
PORTS=(8081 8082 8083 8084 8086 8087 8088 8089 8090 8091)
echo "==> [2] 等待 10 个进程健康（/actuator/health）..."
for port in "${PORTS[@]}"; do
  for i in $(seq 1 60); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$port/actuator/health"; then
      echo "    端口 $port 就绪"
      break
    fi
    sleep 2
  done
done

# 3) 播种秒杀配额（catalog-service, 端口 8082）
SKU_ID="${SKU_ID:-1}"
SECKILL_TOTAL="${SECKILL_TOTAL:-1000}"
echo "==> [3] 播种秒杀配额 skuId=$SKU_ID total=$SECKILL_TOTAL"
curl -fsS -X POST "http://127.0.0.1:8082/internal/stock/seckill/seed?skuId=$SKU_ID&total=$SECKILL_TOTAL" \
  && echo "  播种 ok"

# 4) 持续发模拟请求（一直发，写入日志，供监控观察实时流量）
EMIT_LOG="$LOG_DIR/continuous-emit.log"
echo "==> [4] 启动持续请求发射器（GET /skus + POST 秒杀扣减），日志: $EMIT_LOG"
(
  count=0
  while true; do
    curl -s -o /dev/null "http://127.0.0.1:8082/skus/$SKU_ID" &
    curl -s -o /dev/null -X POST "http://127.0.0.1:8082/internal/stock/seckill/deduct?skuId=$SKU_ID&quantity=1" &
    count=$((count + 1))
    # 配额耗尽则补种，保持秒杀扣减持续有成功流量
    if [ $((count % 1000)) -eq 0 ]; then
      curl -s -o /dev/null -X POST "http://127.0.0.1:8082/internal/stock/seckill/seed?skuId=$SKU_ID&total=$SECKILL_TOTAL"
    fi
    sleep 0.05
  done
) >> "$EMIT_LOG" 2>&1 &
EMIT_PID=$!
echo "$EMIT_PID continuous-emit" >> "$PID_FILE"
echo "    持续发射器 PID ${EMIT_PID}（约 40 req/s，GET 缓存读 + 秒杀扣减）"

# 5) 打开监控
echo ""
echo "=================================================="
echo "  监控入口（在浏览器打开即可看到实时指标）："
echo "    Grafana    http://localhost:3000   （admin/admin，内置「PaymentArch 业务指标」看板）"
echo "    Prometheus http://localhost:9090   （查 http_server_requests_seconds_count / order_created / catalog_seckill_* ）"
echo "    演示控制台 http://localhost:8091/demo   （下单 → 收银台 → 轮询状态）"
echo "=================================================="
case "$(uname -s)" in
  MINGW*|CYGWIN*|MSYS*) start http://localhost:3000 >/dev/null 2>&1 || true ;;
  Darwin*)               open  http://localhost:3000 >/dev/null 2>&1 || true ;;
  *)                     xdg-open http://localhost:3000 >/dev/null 2>&1 || echo "（请手动打开 http://localhost:3000）" ;;
esac

# 6) 压力测试（峰值冲刺，制造可观测尖峰）
echo "==> [6] 运行秒杀压测场景（deployment/performance/catalog-seckill-loadgen.js）..."
( cd deployment/performance && node catalog-seckill-loadgen.js )
echo "    压测产物：deployment/performance/results/load-result.json"

echo ""
echo "▶ 持续发射器仍在后台运行，Grafana/Prometheus 中可见实时曲线。"
echo "  停止方式："
echo "    bash deployment/stop-all.sh"
echo "    kill $EMIT_PID   # 停止持续发射器"
