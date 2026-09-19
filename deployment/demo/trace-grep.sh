#!/usr/bin/env bash
# demo/trace-grep.sh —— 按 traceId / bizNo 捞全链路日志（spec 021 FR-006 + spec 029 FR-607）
#
# 用法：
#   bash demo/trace-grep.sh <traceId>            # 跨全部服务日志捞该 traceId 的所有行
#   bash demo/trace-grep.sh --bizno <bizNo>      # 按业务单号（orderNo / paymentNo / refundNo）捞
#   bash demo/trace-grep.sh <traceId> <额外过滤>  # 位置过滤仍可用
#
# 两者的区别（为什么要两个维度）：
#   - traceId 串起**一次请求**及其触发的后台/异步动作（跨 MQ 边界连续，FR-601~604）；
#   - bizNo 串起**一笔业务事实**的完整历史——同一订单被查询/回调/退款多次即多个 traceId，
#     只有 bizNo 稳定，故排障「这笔订单到底经历了什么」要用 --bizno。
#
# 说明：
#   - 日志行格式（logback-spring.xml 统一 pattern）固定含 traceId= / bizNo= 字段，直接文本匹配；
#     入口请求（HTTP）与后台动作（Scheduler 入口 runWithNewTrace / MQ 消费）共用 traceId，
#     故一次能捞出「请求 + 触发的定时任务/异步动作」全链路（AC4.2 / SC-003 / SC-11）；
#   - 旧格式日志（无 bizNo 列）按 --bizno 检索会漏，属预期（格式升级前的历史日志）；
#   - 输出按 [服务名] 行首前缀标注来源，多文件命中按文件修改时间排序（早 → 晚）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${LOG_DIR:-$HERE/../logs}"

MODE="trace"
if [ "${1:-}" = "--bizno" ] || [ "${1:-}" = "--biz-no" ]; then
  MODE="bizno"; shift
fi

if [ $# -lt 1 ]; then
  cat >&2 <<'USAGE'
用法:
  trace-grep.sh <traceId> [附加grep过滤]
  trace-grep.sh --bizno <bizNo> [附加grep过滤]

示例:
  bash demo/trace-grep.sh 4f2a1c9e-...
  bash demo/trace-grep.sh --bizno ORDER-000000000000001
  bash demo/trace-grep.sh --bizno PAY-000000000000001 FAIL
USAGE
  exit 1
fi

KEY="$1"; shift
EXTRA_FILTER="${1:-}"

if [ ! -d "$LOG_DIR" ] || ! ls "$LOG_DIR"/*.log >/dev/null 2>&1; then
  echo "❌ $LOG_DIR 下无 *.log（服务未启动或 LOG_DIR 指错）" >&2
  exit 1
fi

if [ "$MODE" = "bizno" ]; then
  # bizNo 值带引号（bizNo="ORDER-..."），用 -F 精确匹配键值对，避免正则元字符问题
  ID_PATTERN="bizNo=\"${KEY}\""
  HINT="（无命中：bizNo=$KEY 在 $LOG_DIR 各服务日志中均未出现——注意旧格式日志无 bizNo 列）"
else
  # 日志 pattern 中 traceId 带引号（traceId="uuid"），兼容带/不带引号两种形态
  ID_PATTERN="traceId=\"?${KEY}"
  HINT="（无命中：traceId=$KEY 在 $LOG_DIR 各服务日志中均未出现）"
fi

HITS=0
# 按修改时间升序（旧 → 新），链路阅读顺序自然
for f in $(ls -tr "$LOG_DIR"/*.log); do
  svc="$(basename "$f" .log)"
  if [ "$MODE" = "bizno" ]; then
    matched="$(grep -F "$ID_PATTERN" "$f" 2>/dev/null || true)"
  else
    matched="$(grep -E "$ID_PATTERN" "$f" 2>/dev/null || true)"
  fi
  if [ -n "$EXTRA_FILTER" ] && [ -n "$matched" ]; then
    matched="$(printf '%s\n' "$matched" | grep -E "$EXTRA_FILTER" || true)"
  fi
  if [ -n "$matched" ]; then
    echo "──────── [$svc] ────────"
    printf '%s\n' "$matched" | while IFS= read -r l; do printf '[%s] %s\n' "$svc" "$l"; done
    HITS=1
  fi
done

if [ "$HITS" -eq 0 ]; then
  echo "$HINT" >&2
  exit 2
fi

if [ "$MODE" = "bizno" ]; then
  echo "" >&2
  echo "提示：上面是 bizNo=$KEY 的全历史。若要追某一次请求的完整链路，" >&2
  echo "      从任一行的 traceId=\"...\" 取值，再执行 trace-grep.sh <traceId>。" >&2
fi
