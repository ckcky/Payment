#!/usr/bin/env bash
# demo/restart-payment.sh <SCENARIO> —— 重启 payment-service 并切换 Mock 渠道场景
# Mock 渠道场景是构造期注入的（ADR-0049），运行期不可热切换，故用重启切换。
# 用法：bash demo/restart-payment.sh BUSINESS_UNKNOWN   # 演示 UNKNOWN
#       bash demo/restart-payment.sh SUCCESS            # 恢复默认成功路径
set -euo pipefail

SCENARIO="${1:-SUCCESS}"
MAVEN_CMD="${MAVEN_CMD:-./mvnw}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/deployment/logs/.pids"

if [ -f "$PID_FILE" ]; then
  PID="$(grep "payment-service" "$PID_FILE" | awk '{print $1}' | head -1)"
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null && echo "killed payment-service ($PID)" || echo "payment-service 进程未找到，直接启动新实例"; fi
fi

cd "$ROOT_DIR"
nohup $MAVEN_CMD -pl payment-service spring-boot:run -Dpayment.channel.mock-scenario="$SCENARIO" \
  > "$ROOT_DIR/deployment/logs/payment-service.log" 2>&1 &
echo "$! payment-service" >> "$PID_FILE"
echo "payment-service 以 mock-scenario=$SCENARIO 重启（PID $!）；等待健康…"
sleep 5
for i in $(seq 1 60); do
  if curl -s -o /dev/null -w '%{http_code}' "$ROOT_DIR" >/dev/null 2>&1; then :; fi
  if curl -s -o /dev/null -w '%{http_code}' "http://localhost:8084/actuator/health" 2>/dev/null | grep -q 200; then
    echo "payment-service UP（mock-scenario=$SCENARIO）"; exit 0
  fi
  sleep 2
done
echo "⚠️ payment-service 未在 120s 内就绪，请检查 deployment/logs/payment-service.log"
