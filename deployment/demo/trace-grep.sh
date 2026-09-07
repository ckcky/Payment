#!/usr/bin/env bash
# demo/trace-grep.sh —— 按 traceId 捞全链路日志（spec 021 / FR-006，ADR-0068 D6）
#
# 用法：
#   bash demo/trace-grep.sh <traceId>          # 跨全部服务日志捞该 traceId 的所有行
#
# 说明：
#   - 日志行格式（logback-spring.xml 统一 pattern）固定含 traceId= 字段，直接文本匹配；
#   - 入口请求（HTTP）与后台动作（Scheduler 入口 runWithNewTrace）共用 traceId，
#     故一次能捞出「请求 + 触发的定时任务/异步动作」全链路（AC4.2 / SC-003）；
#   - 输出按 [服务名] 行首前缀标注来源，多文件命中按文件修改时间排序（早 → 晚）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${LOG_DIR:-$HERE/../logs}"

if [ $# -lt 1 ]; then
  echo "用法: $0 <traceId> [附加grep过滤]" >&2
  exit 1
fi

TRACE_ID="$1"; shift
if [ ! -d "$LOG_DIR" ] || ! ls "$LOG_DIR"/*.log >/dev/null 2>&1; then
  echo "❌ $LOG_DIR 下无 *.log（服务未启动或 LOG_DIR 指错）" >&2
  exit 1
fi

HITS=0
# 按修改时间升序（旧 → 新），链路阅读顺序自然
for f in $(ls -tr "$LOG_DIR"/*.log); do
  svc="$(basename "$f" .log)"
  # 日志 pattern 中 traceId 带引号（traceId="uuid"），兼容带/不带引号两种形态
  matched="$(grep -E "traceId=\"?${TRACE_ID}" "$f" 2>/dev/null || true)"
  if [ -n "$matched" ]; then
    echo "──────── [$svc] ────────"
    printf '%s\n' "$matched" | while IFS= read -r l; do printf '[%s] %s\n' "$svc" "$l"; done
    HITS=1
  fi
done

if [ "$HITS" -eq 0 ]; then
  echo "（无命中：traceId=$TRACE_ID 在 $LOG_DIR 各服务日志中均未出现）" >&2
  exit 2
fi
