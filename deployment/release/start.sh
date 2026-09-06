#!/usr/bin/env bash
#
# start.sh —— 发布包一键启动（预构建二进制，无需 Maven / 源码）
#
# 前置：JDK 21(LTS) + Docker(含 Compose v2)；无需 Maven、无需联网构建
#       （首次会拉取 Docker 镜像，视网络数分钟）
# 行为：环境检查 → 基础设施容器(MySQL/Redis/Nacos/Prometheus/Grafana/Loki) →
#       建库建表(幂等，重放 deployment/schema/*.sql) → 后台 java -jar 拉起
#       10 个 JVM 进程(9 领域服务 + mock-channel-web 演示组件) → 等健康
#
# 可选环境变量：
#   PAYMENT_NACOS_IP=<ip>   多网卡机器强制服务向 Nacos 注册指定 IP（默认自动）
#   JAVA_TOOL_OPTIONS=...   JVM 内存上限（默认 -Xmx512m -Xms128m -XX:MaxMetaspaceSize=192m）
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "==> 环境检查"
if ! command -v java >/dev/null 2>&1; then
  echo "✗ 未找到 java。请安装 JDK 21 LTS（https://adoptium.net）。" >&2
  exit 1
fi
JAVA_VER="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ "${JAVA_VER:-0}" -lt 21 ]; then
  echo "✗ 需要 JDK 21，当前为 ${JAVA_VER}。请安装 JDK 21 LTS。" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker。请安装并启动 Docker Desktop（Windows/macOS）或 docker 引擎（Linux）。" >&2
  exit 1
fi
JAR_COUNT=$(ls jars/*.jar 2>/dev/null | wc -l | tr -d ' ')
[ "$JAR_COUNT" = "10" ] || { echo "✗ jars/ 下应有 10 个服务 jar，实际 ${JAR_COUNT}。包不完整？" >&2; exit 1; }
echo "    JDK ${JAVA_VER} ✓ · docker ✓ · jars ✓"

if [ -z "${JAVA_TOOL_OPTIONS:-}" ]; then
  export JAVA_TOOL_OPTIONS="-Xmx512m -Xms128m -XX:MaxMetaspaceSize=192m"
  echo "    已设置默认 JVM 内存上限 JAVA_TOOL_OPTIONS='-Xmx512m -Xms128m -XX:MaxMetaspaceSize=192m'（可用环境变量覆盖）"
fi

LOG_DIR="$ROOT_DIR/deployment/logs"
PID_FILE="$LOG_DIR/.pids"
mkdir -p "$LOG_DIR"
: > "$PID_FILE"

echo ""
echo "==> [1/3] 启动基础设施容器（MySQL / Redis / Nacos / Prometheus / Grafana / Loki / Promtail）"
docker compose -f deployment/docker-compose.yml up -d

echo "==> [1b] 等待 Nacos (8848) 就绪..."
for i in $(seq 1 45); do
  if curl -fsS "http://127.0.0.1:8848/nacos/v1/ns/operator/metrics" 2>/dev/null | grep -q UP; then
    echo "    Nacos 已就绪"
    break
  fi
  [ "$i" = "45" ] && { echo "✗ Nacos 未在 90s 内就绪，请检查: docker logs payment-nacos" >&2; exit 1; }
  sleep 2
done

echo "==> [1c] 等待 MySQL 就绪 → 建表（幂等，重放 deployment/schema/*.sql）"
for i in $(seq 1 30); do
  if docker exec payment-mysql mysql -uroot -proot -e "SELECT 1" >/dev/null 2>&1; then
    echo "    MySQL 已就绪"
    break
  fi
  [ "$i" = "30" ] && { echo "✗ MySQL 未就绪，请检查: docker logs payment-mysql" >&2; exit 1; }
  sleep 2
done
for f in deployment/schema/[0-9][0-9]-*.sql; do
  docker exec -i payment-mysql mysql -uroot -proot < "$f"
  echo "    applied $(basename "$f")"
done

echo "==> [2/3] 后台启动 10 个 JVM 进程（java -jar，无需构建）"
# 演示环境默认值（与源码版 deployment/start-all.sh 保持一致，可用环境变量覆盖）
export PAYMENT_CHANNEL_SECRET="${PAYMENT_CHANNEL_SECRET:-demo-channel-secret-2026}"
export PAYMENT_ADMIN_TOKEN="${PAYMENT_ADMIN_TOKEN:-demo-admin-token}"
export PAYMENT_MOCK_CASHIER_ENABLED="${PAYMENT_MOCK_CASHIER_ENABLED:-true}"

# 多网卡机器可 PAYMENT_NACOS_IP=<ip> 强制注册 IP（Feign 经 Nacos 服务名寻址）
NACOS_IP_OPTS=()
if [ -n "${PAYMENT_NACOS_IP:-}" ]; then
  NACOS_IP_OPTS=(-Dspring.cloud.nacos.discovery.ip="$PAYMENT_NACOS_IP")
  echo "    服务将向 Nacos 注册 IP: $PAYMENT_NACOS_IP"
fi

for jar in jars/*.jar; do
  name="$(basename "$jar" -0.1.0-SNAPSHOT.jar)"
  nohup java "${NACOS_IP_OPTS[@]}" -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo "$! $name" >> "$PID_FILE"
  echo "    $name  (PID $!)"
done

echo "==> [3/3] 等待服务健康（最长 120s，超时仅提示不阻断）"
for port in 8081 8082 8083 8084 8086 8087 8088 8089 8090 8091; do
  ok=0
  for i in $(seq 1 60); do
    if curl -fsS "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then ok=1; break; fi
    sleep 2
  done
  if [ "$ok" = "1" ]; then echo "    :$port UP"; else echo "    :$port ⚠ 未就绪（查看 deployment/logs/）"; fi
done

echo ""
echo "=================================================="
echo "  启动完成。入口："
echo "    演示控制台   http://localhost:8091/demo            （下单 → 收银台 → 退款 → 全链路落库）"
echo "    审计控制台   http://localhost:8091/audit.html      （四核对 / 挂账调账闭环）"
echo "    Grafana      http://localhost:3000   （admin/admin，PaymentArch · SRE 黄金指标）"
echo "    Prometheus   http://localhost:9090"
echo "    MySQL        localhost:3306  （root/root）"
echo "=================================================="
echo "  首次使用请先灌演示种子：bash reset-demo.sh"
echo "  一键跑全部演示场景：    bash demo/run-all.sh"
echo "  实时日志：              tail -f deployment/logs/payment-service.log"
echo "  停止全部：              bash stop.sh"
