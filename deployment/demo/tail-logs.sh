#!/usr/bin/env bash
# demo/tail-logs.sh —— 全栈实时日志合并视图（spec 021 / FR-006，ADR-0068 D6）
#
# 用法：
#   bash demo/tail-logs.sh                # 全部服务实时合并
#   bash demo/tail-logs.sh <关键词>       # 附加过滤（如订单号 / ACCESS / traceId=xxx）
#
# 前置：服务经 start-all.sh / run-payment-services.sh 启动（日志落在 deployment/logs/<服务>.log）。
# 零依赖（仅 tail/sed/grep），Git Bash 兼容。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${LOG_DIR:-$HERE/../logs}"

if [ ! -d "$LOG_DIR" ] || ! ls "$LOG_DIR"/*.log >/dev/null 2>&1; then
  echo "❌ $LOG_DIR 下无 *.log（服务未启动或 LOG_DIR 指错）" >&2
  exit 1
fi

FILTER="${1:-}"

echo "==> tail -f $LOG_DIR/*.log（过滤：${FILTER:-无}，Ctrl-C 退出）"

# tail -f 多文件时输出带 ==> file <== 分隔头；逐行加工：
#   分隔头 → 「──────── [服务名] ────────」；日志行 → 加 [服务名] 前缀（文件名推导）。
# 末尾 || true：Ctrl-C / 下游截断（如 head）导致的 SIGPIPE 噪音不算失败
tail -n 40 -f "$LOG_DIR"/*.log | while IFS= read -r line; do
  case "$line" in
    "==>"*)
      svc="$(sed -E 's|==>.*/([^/]+)\.log <==|\1|' <<<"$line")"
      printf '──────── [%s] ────────\n' "$svc"
      ;;
    "")
      ;;
    *)
      if [ -z "$FILTER" ] || grep -q -- "$FILTER" <<<"$line"; then
        printf '[%s] %s\n' "$svc" "$line"
      fi
      ;;
  esac
done || true
