#!/usr/bin/env bash
# demo/restart-payment.sh <SCENARIO> [PER_CHANNEL] —— 重启 payment-service 并切换 Mock 渠道场景
# Mock 渠道场景是构造期注入的（ADR-0049），运行期不可热切换，故用重启切换。
# 用法：bash demo/restart-payment.sh BUSINESS_UNKNOWN   # 演示 UNKNOWN
#       bash demo/restart-payment.sh SUCCESS            # 恢复默认成功路径
#       bash demo/restart-payment.sh SUCCESS "WECHAT=FAILURE"   # per-channel 人格（Feature 028 / FR-010）
#       bash demo/restart-payment.sh SUCCESS "ALIPAY=SUCCESS,WECHAT=FAILURE"  # 多渠道路径
#
# PER_CHANNEL（可选，Feature 028 / FR-014）：逗号分隔的 `CODE=SCENARIO` 列表，写入
# `payment.channel.adapters.<CODE>.scenario`，覆盖全局 mock-scenario 作为该渠道的默认场景。
# 渠道码大小写不敏感（Spring 松散绑定 + Registry 归一大写）。
#
# 双模式（spec 026 / ADR-0070）：
#   - 宿主模式：杀掉监听 8084 的 JVM，再以 -Dpayment.channel.mock-scenario=$SCENARIO 重新拉起。
#   - 容器模式：以 PAYMENT_CHANNEL_MOCK_SCENARIO=$SCENARIO 重建 payment-service 容器
#     （compose 里该变量已提升为可注入项，见 docker-compose.yml payment-service.environment）。
#   模式自动判定，调用方（run-all.sh）无需改动（FR-009 / T503）。
set -euo pipefail

SCENARIO="${1:-SUCCESS}"
PER_CHANNEL="${2:-}"
MAVEN_CMD="${MAVEN_CMD:-./mvnw}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PID_FILE="$ROOT_DIR/deployment/logs/.pids"
PORT=8084
COMPOSE_FILE="$ROOT_DIR/deployment/docker-compose.yml"

# 把 "WECHAT=FAILURE,ALIPAY=SUCCESS" 转成 JVM 参数数组（per-channel 优先级高于全局）。
JVM_ARGS=("-Dpayment.channel.mock-scenario=$SCENARIO")
ENV_ARGS=("PAYMENT_CHANNEL_MOCK_SCENARIO=$SCENARIO")
SPRING_ARGS=("--payment.channel.mock-scenario=$SCENARIO")
if [ -n "$PER_CHANNEL" ]; then
  IFS=',' read -ra _PAIRS <<< "$PER_CHANNEL"
  for _pair in "${_PAIRS[@]}"; do
    _code="$(echo "${_pair%%=*}" | tr -d ' ' | tr '[:lower:]' '[:upper:]')"
    _scn="$(echo "${_pair#*=}" | tr -d ' ')"
    [ -n "$_code" ] && [ -n "$_scn" ] && [ "$_code" != "$_pair" ] || {
      echo "⚠️ 忽略非法 per-channel 片段：${_pair}（期望 CODE=SCENARIO）" >&2; continue; }
    JVM_ARGS+=("-Dpayment.channel.adapters.${_code}.scenario=${_scn}")
    ENV_ARGS+=("PAYMENT_CHANNEL_${_code}_SCENARIO=${_scn}")
    SPRING_ARGS+=("--payment.channel.adapters.${_code}.scenario=${_scn}")
  done
fi

# 兼容 Git Bash 沙箱（MSYS_NO_PATHCONV 会让 Windows curl 写 /dev/null 失败，见 lib.sh）
unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

wait_healthy() {
  for i in $(seq 1 60); do
    if curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q 200; then
      echo "payment-service UP（mock-scenario=${SCENARIO}，模式=${1}）"
      return 0
    fi
    sleep 2
  done
  echo "⚠️ payment-service 未在 120s 内就绪，请检查 deployment/logs/payment-service.log"
  return 1
}

wait_nacos_registered() {
  # 健康 200 ≠ 服务发现可用：容器重建存在「旧实例已下线、新实例未注册」窗口，
  # order 侧 Feign 会报 No servers available for service: payment-service
  # （2026-09-22 035 demo 实测）。轮询 Nacos 实例列表直到新实例注册，再留 3s 让订阅端收敛。
  for i in $(seq 1 30); do
    if curl -s --noproxy '*' "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=payment-service" 2>/dev/null \
        | grep -q '"ip"'; then
      sleep 3
      echo "payment-service 已在 Nacos 注册（第 $i 次探测）"
      return 0
    fi
    sleep 1
  done
  echo "⚠️ payment-service 未在 Nacos 出现（30s+），后续场景可能 No servers available" >&2
}

# ---- 模式判定：容器模式下 payment-service 由 compose 管理，不是宿主 JVM ----
# 判定依据：compose 中 payment-service 正在运行。docker 不可用或容器未起 → 一律回落宿主模式，
# 保证 IDE 断点调试等既有路径不被破坏（FR-009 / T502）。
MODE=host
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  if docker compose -f "$COMPOSE_FILE" --profile full ps --services --filter status=running 2>/dev/null \
      | grep -qx "payment-service"; then
    MODE=container
  fi
fi
echo "模式判定：${MODE}"

if [ "$MODE" = "container" ]; then
  echo "以 ${ENV_ARGS[*]} 重建 payment-service 容器…"
  # --no-deps：只重建 payment 自身，避免连带重启 mysql/nacos。
  # --force-recreate：环境变量变化在部分 compose 版本下不会触发重建，显式强制以确保场景生效。
  env "${ENV_ARGS[@]}" \
    docker compose -f "$COMPOSE_FILE" --profile full up -d --force-recreate --no-deps payment-service
  wait_healthy container
  wait_nacos_registered
  exit 0
fi

# ---- 宿主模式：终止现有 payment-service ----
# 以【端口】为准而非 .pids 文件：.pids 会随多次重启堆积陈旧条目，且 Git Bash 的 kill
# 对其它 shell 会话启动的 Windows 进程通常无效，必须 taskkill 按 Windows PID 兜底。
#
# 平台分支：macOS 的 netstat 不支持 -o（BSD 版），-ano 会直接报错；配合本文件顶部的
# `set -euo pipefail`，netstet 的非 0 退出码会让整个管道返回非 0，进而令
# `PIDS="$(port_pids)"` 触发 set -e 静默退出（2026-09-09 实测：run-all.sh 在退款场景
# 之后无任何输出直接 EXIT=1，就是这个原因）。故 macOS 走 lsof，并统一 `|| true` 兜底。
# 只取【监听】该端口的进程。`lsof -ti tcp:$PORT` 不带 -sTCP:LISTEN 会把所有与 8084
# 有 TCP 关联的进程都返回——包括 order / reconciliation 等调用方持有的出站连接，
# 届时下面的 kill 会连带杀掉无辜服务（2026-09-09 实测：一次重启干掉了 9 个进程）。
port_pids() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | sort -u || true
  else
    netstat -ano 2>/dev/null | awk '/LISTENING/ && $2 ~ /:'"$PORT"'$/ {print $NF}' | sort -u || true
  fi
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
  nohup java "${JVM_ARGS[@]}" -jar "$JAR" \
    > "$ROOT_DIR/deployment/logs/payment-service.log" 2>&1 &
else
  # 注意：spring-boot:run 默认 fork 独立 JVM，直接 -D<prop> 留在 Maven 进程里传不进去，
  # 必须经 spring-boot.run.jvmArguments 注入（ADR-0049 场景为构造期注入）。
  # --server.port 显式写死为 $PORT：启动环境若存在 SERVER_PORT / PORT 之类的变量，
  # Spring 的环境变量优先级高于 application.yml，实测会把服务起在错误端口上
  # （2026-09-09：三个服务被拉到 60956 而启动失败）。本脚本只管 8084，显式指定最稳。
  nohup $MAVEN_CMD -pl payment-service spring-boot:run \
    -Dspring-boot.run.jvmArguments="${JVM_ARGS[*]}" \
    -Dspring-boot.run.arguments="--server.port=$PORT $(printf '%s ' "${SPRING_ARGS[@]}")" \
    > "$ROOT_DIR/deployment/logs/payment-service.log" 2>&1 &
fi
echo "$! payment-service" >> "$PID_FILE"
echo "payment-service 以 mock-scenario=$SCENARIO 重启（PID $!）；等待健康…"
sleep 5
wait_healthy host
wait_nacos_registered
