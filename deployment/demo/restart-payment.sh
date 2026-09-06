#!/usr/bin/env bash
# demo/restart-payment.sh <SCENARIO> —— 重启 payment-service 并切换 Mock 渠道场景
# Mock 渠道场景是构造期注入的（ADR-0049），运行期不可热切换，故用重启切换。
# 用法：bash demo/restart-payment.sh BUSINESS_UNKNOWN   # 演示 UNKNOWN
#       bash demo/restart-payment.sh SUCCESS            # 恢复默认成功路径
set -euo pipefail

SCENARIO="${1:-SUCCESS}"
MAVEN_CMD="${MAVEN_CMD:-./mvnw}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PID_FILE="$ROOT_DIR/deployment/logs/.pids"
PORT=8084

# 兼容 Git Bash 沙箱（MSYS_NO_PATHCONV 会让 Windows curl 写 /dev/null 失败，见 lib.sh）
unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

# ---- 终止现有 payment-service ----
# 以【端口】为准而非 .pids 文件：.pids 会随多次重启堆积陈旧条目，且 Git Bash 的 kill
# 对其它 shell 会话启动的 Windows 进程通常无效，必须 taskkill 按 Windows PID 兜底。
port_pids() {
  netstat -ano 2>/dev/null | awk '/LISTENING/ && $2 ~ /:'"$PORT"'$/ {print $NF}' | sort -u
}

PIDS="$(port_pids)"
if [ -n "$PIDS" ]; then
  for p in $PIDS; do
    kill "$p" 2>/dev/null || taskkill //F //PID "$p" >/dev/null 2>&1 || true
    echo "killed payment-service listener (PID $p)"
  done
  # 等待端口真正释放（进程退出是异步的，立刻重启会 Port already in use）
  for i in $(seq 1 15); do
    [ -z "$(port_pids)" ] && break
    sleep 1
  done
  if [ -n "$(port_pids)" ]; then
    echo "⚠️ 端口 $PORT 仍被占用，重启可能失败（deployment/logs/payment-service.log）" >&2
  fi
fi

cd "$ROOT_DIR"
# 双模式：发布包（jars/payment-service-*.jar 存在）→ java -jar 直跑；
# 源码仓库 → spring-boot:run（场景为构造期注入 ADR-0049，运行期不可热切换）。
JAR="$ROOT_DIR/jars/payment-service-0.1.0-SNAPSHOT.jar"
if [ -f "$JAR" ]; then
  nohup java "-Dpayment.channel.mock-scenario=$SCENARIO" -jar "$JAR" \
    > "$ROOT_DIR/deployment/logs/payment-service.log" 2>&1 &
else
  # 注意：spring-boot:run 默认 fork 独立 JVM，直接 -D<prop> 留在 Maven 进程里传不进去，
  # 必须经 spring-boot.run.jvmArguments 注入（ADR-0049 场景为构造期注入）。
  nohup $MAVEN_CMD -pl payment-service spring-boot:run \
    -Dspring-boot.run.jvmArguments="-Dpayment.channel.mock-scenario=$SCENARIO" \
    > "$ROOT_DIR/deployment/logs/payment-service.log" 2>&1 &
fi
echo "$! payment-service" >> "$PID_FILE"
echo "payment-service 以 mock-scenario=$SCENARIO 重启（PID $!）；等待健康…"
sleep 5
for i in $(seq 1 60); do
  if curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q 200; then
    echo "payment-service UP（mock-scenario=${SCENARIO}）"; exit 0
  fi
  sleep 2
done
echo "⚠️ payment-service 未在 120s 内就绪，请检查 deployment/logs/payment-service.log"
