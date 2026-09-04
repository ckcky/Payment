#!/usr/bin/env bash
# =============================================================================
# stop-traffic.sh — Feature 015 / P6：停止后台全链路流量脚本
#
# 用法：bash deployment/demo/stop-traffic.sh
# 依据 traffic-gen.sh 写入的 PID 文件终止进程（并清理其子进程）。
# =============================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$SCRIPT_DIR/traffic-gen.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "[INFO] 未找到 $PID_FILE —— traffic-gen 未在运行（或已停止）"
  exit 0
fi

PID=$(cat "$PID_FILE" 2>/dev/null)
if [ -z "$PID" ]; then
  echo "[INFO] PID 文件为空，清理之"; rm -f "$PID_FILE"; exit 0
fi

if kill -0 "$PID" 2>/dev/null; then
  # 先杀子进程（curl/sleep），再杀主进程
  pkill -P "$PID" 2>/dev/null || true
  kill "$PID" 2>/dev/null || true
  sleep 1
  kill -0 "$PID" 2>/dev/null && { kill -9 "$PID" 2>/dev/null || true; }
  echo "[INFO] 已停止 traffic-gen（PID $PID）"
else
  echo "[INFO] PID $PID 已不在运行"
fi
rm -f "$PID_FILE"
echo "[INFO] 统计文件：$SCRIPT_DIR/traffic-gen.stats"
