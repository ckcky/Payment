#!/usr/bin/env bash
# demo/scenario-refund.sh —— 退款演示：成功支付 → 退款（累计不超限 + 幂等重放）
# 前置：服务已启动；payment-service 以默认 mock-scenario=SUCCESS 运行（bash demo/restart-payment.sh SUCCESS）
# 断言：支付 SUCCEEDED → 退款 CREATED → 同一幂等键重放返回同一退款（不重复）→ 超额退款被 409 拒（H1 防超额）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

echo "==> ① 解析种子 SKU（DEMO-SKU-101，¥99.00）"
http GET "$CATALOG_URL/skus"
assert_status 200 "SKU 列表"
SKU_ID="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);m=[x for x in d if x.get('skuCode')=='DEMO-SKU-101'];print(m[0]['id'] if m else '')")"
[ -n "$SKU_ID" ] || fail "未找到种子 SKU DEMO-SKU-101（请先 bash demo/reset.sh）"
AMOUNT=9900

echo "==> ② 下单 + 显式选渠道建支付单（Feature 015 两步式；默认 SUCCESS 场景）"
http POST "$ORDER_URL/orders" "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
assert_status 201 "下单"
jget "d['orderNo']"; ORDER_NO="$VALUE"
[ -n "$ORDER_NO" ] || fail "下单响应缺失 orderNo"
http POST "$ORDER_URL/orders/$ORDER_NO/payments" '{"channelCode":"alipay"}'
assert_status 201 "选渠道建支付单"
jget "d['paymentNo']"; PAYMENT_NO="$VALUE"
[ -n "$PAYMENT_NO" ] || fail "建支付单响应缺少 paymentNo"
info "orderNo=$ORDER_NO paymentNo=$PAYMENT_NO"
http GET "$PAYMENT_URL/payments/$PAYMENT_NO"
jget "d['status']"; PAY_STATUS="$VALUE"

# cashier 路径兼容（PAYMENT_MOCK_CASHIER_ENABLED=true 时支付停在 PROCESSING）：
# 此时代渠道补发签名回调，把支付推到 SUCCEEDED，与同步 charge 形态收敛到同一状态。
if [ "$PAY_STATUS" = "PROCESSING" ]; then
  info "检测到 cashier 路径（PROCESSING），代渠道补发签名回调"
  http POST "$DEMO_URL/mock-channel/callback" \
    "{\"paymentNo\":\"$PAYMENT_NO\",\"status\":\"SUCCESS\",\"channelReference\":\"refund-demo-$ORDER_NO\",\"amountMinor\":$AMOUNT,\"signMode\":\"VALID\"}"
  assert_status 200 "渠道回调受理"
  http GET "$PAYMENT_URL/payments/$PAYMENT_NO"
  jget "d['status']"; PAY_STATUS="$VALUE"
fi
assert_eq "$PAY_STATUS" "SUCCEEDED" "支付 → SUCCEEDED"

# 幂等键动态生成：idempotency_key 全局唯一（uk_refunds_idempotency_key），
# 写死的键跨运行会命中重放、污染后续断言（无 reset 直跑复现过）
IDEM_BASE="rk-$(date +%s)-$$"
echo "==> ③ 发起退款（¥50.00，幂等键 $IDEM_BASE-1）"
http POST "$REFUND_URL/internal/refunds" "{\"orderNo\":\"$ORDER_NO\",\"paymentNo\":\"$PAYMENT_NO\",\"userId\":\"demo-user\",\"amountMinor\":5000,\"currencyCode\":\"CNY\",\"reason\":\"demo-partial\",\"idempotencyKey\":\"$IDEM_BASE-1\"}"
assert_status 200 "退款创建"
jget "d['refundNo']"; REFUND_NO="$VALUE"
jget "d['status']"; REFUND_STATUS="$VALUE"
# 退款创建后状态取决于渠道形态：同步收敛为 SUCCEEDED；异步渠道为 CREATED（待回调/确认）。二者均合法。
case "$REFUND_STATUS" in
  CREATED|SUCCEEDED) info "PASS: 退款创建（状态 ${REFUND_STATUS}）" ;;
  *) fail "退款创建: 非预期状态 [$REFUND_STATUS]" ;;
esac
info "refundNo=$REFUND_NO"

echo "==> ④ 同一幂等键重放 → 返回同一退款（不重复创建）"
http POST "$REFUND_URL/internal/refunds" "{\"orderNo\":\"$ORDER_NO\",\"paymentNo\":\"$PAYMENT_NO\",\"userId\":\"demo-user\",\"amountMinor\":5000,\"currencyCode\":\"CNY\",\"reason\":\"demo-partial\",\"idempotencyKey\":\"$IDEM_BASE-1\"}"
assert_status 200 "幂等重放受理"
jget "d['refundNo']"; REFUND_NO2="$VALUE"
assert_eq "$REFUND_NO2" "$REFUND_NO" "幂等重放返回同一退款单号（不重复）"

echo "==> ⑤ 超额退款被拒（累计 5000+6000=11000 > 已付 9900，触发 H1 防超额）"
http POST "$REFUND_URL/internal/refunds" "{\"orderNo\":\"$ORDER_NO\",\"paymentNo\":\"$PAYMENT_NO\",\"userId\":\"demo-user\",\"amountMinor\":6000,\"currencyCode\":\"CNY\",\"reason\":\"demo-over\",\"idempotencyKey\":\"$IDEM_BASE-2\"}"
# 现网契约：超额不回 409，而是受理为 REJECTED 退款记录（HTTP 200 + status=REJECTED，资金约束落领域状态）
assert_status 200 "超额退款受理"
jget "d['status']"; REJ_STATUS="$VALUE"
assert_eq "$REJ_STATUS" "REJECTED" "超额退款 → REJECTED（H1 防超额）"

echo ""
info "scenario-refund 全部断言通过 ✅"
