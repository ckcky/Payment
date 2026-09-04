#!/usr/bin/env bash
# demo/scenario-reconciliation.sh —— 对账演示：跑批 → 列差异 → 关闭门禁反例 → 处理 → 关闭 → 结算汇总
# 前置：服务已启动；已存在若干支付/退款（可先跑 scenario-happy-path / scenario-refund）。
# 断言：批次产生差异 → 有关联差异时关闭被拒（400）→ 处理全部差异 → 关闭成功（200 → CLOSED）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

# 周期带时分秒：同日重复跑 run-all 不会复用上一批（上批差异已处理完，门禁反例会失效）
PERIOD="demo-$(date +%Y%m%d%H%M%S)"
echo "==> ① 触发对账批（周期 $PERIOD）"
http POST "$RECON_URL/internal/reconciliation/batches" "{\"period\":\"$PERIOD\"}"
assert_status 200 "对账批创建"
jget "d['id']"; BATCH_ID="$VALUE"
jget "d['status']"; BATCH_STATUS="$VALUE"
info "batchId=$BATCH_ID status=$BATCH_STATUS"

echo "==> ② 列出差异"
http GET "$RECON_URL/internal/reconciliation/batches/$BATCH_ID/differences"
assert_status 200 "差异列表"
DIFF_COUNT="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);print(len(d) if isinstance(d,list) else 0)")"
info "差异数=$DIFF_COUNT（样例账单 vs 真实支付，不一致即演示差异收敛）"

echo "==> ③ 关闭门禁反例：尚有未处理差异时关闭应被拒（400）"
http POST "$RECON_URL/internal/reconciliation/batches/$BATCH_ID/close" "{\"operator\":\"demo-auditor\"}"
assert_status 400 "未处理差异时关闭被拒（门禁生效）"

echo "==> ④ 处理全部差异"
if [ "${DIFF_COUNT:-0}" -gt 0 ] 2>/dev/null; then
  http GET "$RECON_URL/internal/reconciliation/batches/$BATCH_ID/differences"
  REFS="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);print(' '.join(str(x.get('reference','')) for x in d))")"
  for ref in $REFS; do
    [ -n "$ref" ] || continue
    http POST "$RECON_URL/internal/reconciliation/batches/$BATCH_ID/differences/resolve" \
      "{\"reference\":\"$ref\",\"resolutionNote\":\"demo-resolve\",\"resolvedBy\":\"demo-auditor\"}"
    assert_status 200 "处理差异 $ref"
  done
else
  info "本周期无差异（账单与事实一致）"
fi

echo "==> ⑤ 全部处理后关闭成功（200 → CLOSED）"
http POST "$RECON_URL/internal/reconciliation/batches/$BATCH_ID/close" "{\"operator\":\"demo-auditor\"}"
assert_status 200 "关闭对账批"
jget "d['status']"; FINAL_STATUS="$VALUE"
assert_eq "$FINAL_STATUS" "CLOSED" "对账批 → CLOSED"

echo "==> ⑥ 结算汇总（信息展示）"
http GET "$RECON_URL/internal/reconciliation/settlement-summary?period=$PERIOD"
assert_status 200 "结算汇总"

echo "==> ⑦ 创建结算批（对账 CLOSED 后按同周期结算，ADR-0023 闸门放行 → settlement.created 指标）"
http POST "$SETTLEMENT_URL/internal/settlements/batches" "{\"merchantId\":\"1\",\"period\":\"$PERIOD\",\"idempotencyKey\":\"settle-$PERIOD\"}"
assert_status 200 "结算批创建"
jget "d['status']"; SETTLE_STATUS="$VALUE"
case "$SETTLE_STATUS" in
  READY|PROCESSING|UNKNOWN|CLOSED) info "PASS: 结算批状态 $SETTLE_STATUS" ;;
  *) fail "结算批创建: 非预期状态 [$SETTLE_STATUS]" ;;
esac

echo ""
info "scenario-reconciliation 全部断言通过 ✅"
