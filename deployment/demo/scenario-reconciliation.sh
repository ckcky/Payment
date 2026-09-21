#!/usr/bin/env bash
# demo/scenario-reconciliation.sh —— 对账演示（032 实账化）：账单导入 → run 编排 → 差异台账分页 →
# 按单号人工收口 → 关闭门禁反例 → 关闭 → 结算汇总 → 结算批
# 前置：服务已启动；已存在若干支付/退款（可先跑 scenario-happy-path / scenario-refund）。
# 断言：导入 NORMALIZED → run 产差异（无导入即 400 STATEMENT_UNAVAILABLE 的 sample 回退已退役）
#       → 有关联差异时关闭被拒（400）→ 按单号 resolve 全部差异 → 关闭成功（200 → CLOSED）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

# 周期带时分秒：同日重复跑 run-all 不会复用上一批（上批差异已处理完，门禁反例会失效）
PERIOD="demo-$(date +%Y%m%d%H%M%S)"

# ---- ⓪ 导入账单（032 T25：run 的账单前置；固定渠道长款行担保差异必现）----
echo "==> ⓪ 导入渠道账单（period=${PERIOD}，channel=MOCK，指纹幂等）"
STMT_PAYLOAD="$(python3 -c "
import json, sys
content = ('referenceType,reference,channelTxnNo,merchantId,amountMinor,feeMinor,currencyCode,status,occurredAt\n'
           'PAYMENT,CH-DEMO-SURPLUS,CH-DEMO-SURPLUS,1,12345,0,CNY,SUCCEEDED,%s\n') % sys.argv[2]
print(json.dumps({'channelCode': 'MOCK', 'period': sys.argv[1], 'sourceType': 'FILE',
                  'content': content, 'importedBy': 'scenario-reconciliation'}))
" "$PERIOD" "$(date '+%Y-%m-%d %H:%M:%S')")"
http POST "$RECON_URL/internal/reconciliation/statement-imports" "$STMT_PAYLOAD"
assert_status 201 "账单导入"
jget "d['status']"
assert_eq "$VALUE" "NORMALIZED" "账单导入 → NORMALIZED"
jget "d['importNo']"; IMPORT_NO="$VALUE"
info "importNo=$IMPORT_NO"

echo "==> ① 触发对账 run（周期 ${PERIOD}，无导入即 400 STATEMENT_UNAVAILABLE——sample 回退已退役）"
http POST "$RECON_URL/internal/reconciliation/run" "{\"period\":\"$PERIOD\",\"channelCode\":\"MOCK\"}"
assert_status 200 "对账 run"
jget "d['id']"; BATCH_ID="$VALUE"
jget "d['batchNo']"; BATCH_NO="$VALUE"
jget "d['status']"; BATCH_STATUS="$VALUE"
info "batchId=$BATCH_ID batchNo=$BATCH_NO status=$BATCH_STATUS"

echo "==> ② 差异台账分页查询（period=${PERIOD}）"
http GET "$RECON_URL/internal/reconciliation/differences?period=$PERIOD&size=50"
assert_status 200 "差异分页查询"
jget "d['total']"; DIFF_TOTAL="$VALUE"
DIFF_NOS="$(echo "$BODY" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(' '.join(x['diffNo'] for x in d['items'] if x.get('diffNo')))
")"
[ "${DIFF_TOTAL:-0}" -ge 1 ] || fail "差异为 0（渠道长款行应产出 CHANNEL_ONLY）"
info "差异数=${DIFF_TOTAL}（含渠道长款 CH-DEMO-SURPLUS；平台侧未匹配事实为 PLATFORM_ONLY）"

echo "==> ③ 关闭门禁反例：尚有未处理差异时关闭应被拒（400）"
http POST "$RECON_URL/internal/reconciliation/batches/$BATCH_ID/close" "{\"operator\":\"demo-auditor\"}"
assert_status 400 "未处理差异时关闭被拒（门禁生效）"

echo "==> ④ 按单号人工收口全部差异（备注必填，RD 单号 resolve）"
for diffNo in $DIFF_NOS; do
  http POST "$RECON_URL/internal/reconciliation/differences/$diffNo/resolve" \
    "{\"resolutionNote\":\"demo-resolve\",\"resolvedBy\":\"demo-auditor\"}"
  assert_status 200 "收口差异 $diffNo"
done
http GET "$RECON_URL/internal/reconciliation/differences?period=$PERIOD&status=PENDING&size=50"
jget "d['total']"; PENDING_LEFT="$VALUE"
[ "${PENDING_LEFT:-0}" -eq 0 ] || fail "仍有 ${PENDING_LEFT} 条 PENDING 差异"
info "PASS: 全部差异按单号收口（台账状态口径）"

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
