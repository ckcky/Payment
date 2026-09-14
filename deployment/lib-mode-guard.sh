#!/usr/bin/env bash
# 模式守卫共享库（spec 026 / ADR-0070 D2）
#
# 背景：宿主模式与容器模式**对外暴露同一批端口 8081–8091**（这是 e2e/压测/demo 脚本零改动的前提），
# 因此两种模式不能同时运行。若不做守卫，后启动的那一侧会 bind 失败，
# 且失败形态是「容器起不来」或「服务日志 Connection refused」这类**假成功**——比直接报错难排查一个数量级。
#
# 本库提供双向检测：
#   guard_no_container_apps  宿主模式启动前调用：检测容器模式是否在跑
#   guard_no_host_apps       容器模式启动前调用：检测宿主进程是否占用端口
#
# 检测口径说明：
#   - 容器侧：以 `docker ps` 中是否存在 payment-<svc> 容器（本项目 container_name 命名约定）为准。
#     不依赖 `compose ps`，因为 compose 命令需要 -f 与 profile 上下文，易误判。
#   - 宿主侧：以端口 8081–8091 是否被监听为准，且**排除 Docker 自己**——
#     容器模式的端口映射同样会让宿主端口处于 LISTEN 状态，故先查容器，
#     只有「容器不存在但端口被占」才判定为宿主进程。
#
# 用法（在脚本中 source 后调用，命中即 exit 1）：
#   source "$(dirname "$0")/lib-mode-guard.sh"
#   guard_no_container_apps "start-all.sh"

APP_PORTS=(8081 8082 8083 8084 8086 8087 8088 8089 8090 8091)
GUARD_APP_CONTAINERS=(
  payment-merchant-service payment-catalog-service payment-order-service
  payment-payment-service payment-fulfillment-service payment-entitlement-service
  payment-reconciliation-service payment-settlement-service payment-ledger-service
  payment-mock-channel-web
)

# 返回 0（真）表示当前有容器模式的应用容器在跑
container_apps_running() {
  command -v docker >/dev/null 2>&1 || return 1
  local running
  running="$(docker ps --format '{{.Names}}' 2>/dev/null || true)"
  [ -z "$running" ] && return 1
  local c
  for c in "${GUARD_APP_CONTAINERS[@]}"; do
    grep -qx "$c" <<< "$running" && return 0
  done
  return 1
}

# 返回 0（真）表示有宿主进程监听应用端口（排除容器映射的情况）
host_apps_listening() {
  local p pid_files=()
  for p in "${APP_PORTS[@]}"; do
    if command -v lsof >/dev/null 2>&1; then
      # 只取 LISTEN 状态的监听者，避免把「有出站连接关联」的进程误判为占用
      local pids
      pids="$(lsof -ti tcp:"$p" -sTCP:LISTEN 2>/dev/null | sort -u || true)"
      [ -n "$pids" ] && pid_files+=("$p")
    elif command -v netstat >/dev/null 2>&1; then
      netstat -ano 2>/dev/null | grep -qE "LISTENING.*:($p)\$" && pid_files+=("$p")
    fi
  done
  [ "${#pid_files[@]}" -gt 0 ]
}

# 宿主模式启动前调用
guard_no_container_apps() {
  local self="${1:-start-all.sh}"
  if container_apps_running; then
    cat >&2 <<EOF
✗ 检测到【容器模式】正在运行（存在 payment-*-service 容器），与 $self（宿主模式）端口冲突。

  两种模式对外都使用 8081–8091，**不可同时运行**（ADR-0070 D2 双轨互斥）。

  请先停止容器模式：
      bash deployment/stop-all.sh
  然后再执行：
      bash $self
EOF
    return 1
  fi
  return 0
}

# 容器模式启动前调用
guard_no_host_apps() {
  local self="${1:-start-container.sh}"
  # 先排除容器模式的自我误判：已存在应用容器时，端口占用是容器映射造成的
  if container_apps_running; then
    return 0
  fi
  if host_apps_listening; then
    cat >&2 <<EOF
✗ 检测到【宿主模式】正在运行（应用端口被宿主进程占用），与 ${self}（容器模式）端口冲突。

  两种模式对外都使用 8081–8091，**不可同时运行**（ADR-0070 D2 双轨互斥）。

  请先停止宿主模式：
      bash deployment/stop-all.sh
  然后再执行：
      bash $self
EOF
    return 1
  fi
  return 0
}
