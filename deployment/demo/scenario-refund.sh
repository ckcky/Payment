#!/usr/bin/env bash
# demo/scenario-refund.sh —— 退款演示（spec 019 / ADR-0067 order 驱动两层退款单）
# 链路：POST /internal/orders/refund 生成 TXRF → payment 生成 PMRF 执行单 →
#       mock 渠道受理 + 异步回调 → payment 收敛（记账冲正 + 通知 order）→ order 收口
# 前置：服务已启动；payment-service 以默认 mock-scenario=SUCCESS + refund-async 运行
# 断言：支付 SUCCEEDED → 双号互记（TXRF/PMRF）→ 同 TXRF 重放幂等 → 超退被 REJECTED →
#       异步回调收敛退款终态
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

echo "==> ③ order 发起退款（¥50.00，两层退款单 TXRF/PMRF 双号互记）"
http POST "$ORDER_URL/internal/orders/refund" "{\"orderNo\":\"$ORDER_NO\",\"amountMinor\":5000,\"reason\":\"demo-partial\"}"
assert_status 200 "退款受理"
jget "d['txrf']"; TXRF="$VALUE"
jget "d['pmrf']"; PMRF="$VALUE"
jget "d['status']"; REFUND_STATUS="$VALUE"
[ -n "$TXRF" ] || fail "退款响应缺失 txrf（交易层退款单）"
[ -n "$PMRF" ] || fail "退款响应缺失 pmrf（支付层退款执行单）"
info "txrf=$TXRF pmrf=$PMRF status=$REFUND_STATUS"
# 受理态：同步收敛 SUCCEEDED 或异步受理 PROCESSING 均合法（mock 默认异步）
case "$REFUND_STATUS" in
  PROCESSING|SUCCEEDED) info "PASS: 退款单受理（状态 ${REFUND_STATUS}）" ;;
  *) fail "退款受理: 非预期状态 [$REFUND_STATUS]" ;;
esac

echo "==> ④ 等待渠道异步回调收敛第一笔退款终态（mock 默认 refund-async，延迟约 1s）"
FINAL_STATUS="PROCESSING"
for i in $(seq 1 30); do
  http GET "$PAYMENT_URL/internal/refunds/$PMRF" || true
  jget "d['status']"; FINAL_STATUS="$VALUE"
  case "$FINAL_STATUS" in
    SUCCEEDED|FAILED|UNKNOWN) break ;;
  esac
  sleep 0.2
done
assert_eq "$FINAL_STATUS" "SUCCEEDED" "异步回调收敛 → 退款 SUCCEEDED（PMRF=${PMRF}）"
jget "d['transactionRefundNo']"; PMSIDE_TXRF="$VALUE"
assert_eq "$PMSIDE_TXRF" "$TXRF" "payment 侧 refunds.transaction_refund_no == TXRF（双号互记）"

echo "==> ⑤ 已收敛后再提交同额请求 → 第二笔为「新退款单」而非回放；4900 剩余可退中 2000 合法"
# 说明：渠道回调已收敛（SUCCEEDED），同额重提交不是「同 TXRF 重试」（REQUESTED/PROCESSING 才回放），
# 而是合法的第二笔部分退款（微信式多次部分退）；同额 5000 会因剩余可退 4900 被拒（409）。
http POST "$ORDER_URL/internal/orders/refund" "{\"orderNo\":\"$ORDER_NO\",\"amountMinor\":2000,\"reason\":\"demo-second\"}"
assert_status 200 "第二笔部分退款受理"
jget "d['txrf']"; TXRF2="$VALUE"
jget "d['pmrf']"; PMRF2="$VALUE"
[ "$TXRF2" != "$TXRF" ] || fail "第二笔退款不应复用第一笔 TXRF"
info "second txrf=$TXRF2 pmrf=$PMRF2"

echo "==> ⑥ 超额退款被 order 侧前置校验拦截（5000+2000+3000=10000 > 已付 9900，H1 防超额）"
http POST "$ORDER_URL/internal/orders/refund" "{\"orderNo\":\"$ORDER_NO\",\"amountMinor\":3000,\"reason\":\"demo-over\"}"
assert_status 409 "超额退款 → 409 AMOUNT_INVARIANT_VIOLATION（refundable=2900）"

echo "==> ⑦ 等第二笔收敛 + 终态核对（订单 PARTIALLY_REFUNDED、TXRF 追踪段可见）"
for i in $(seq 1 30); do
  http GET "$PAYMENT_URL/internal/refunds/$PMRF2" || true
  jget "d['status']"; FINAL2="$VALUE"
  case "$FINAL2" in
    SUCCEEDED|FAILED|UNKNOWN) break ;;
  esac
  sleep 0.2
done
assert_eq "$FINAL2" "SUCCEEDED" "第二笔退款收敛 SUCCEEDED（PMRF=${PMRF2}）"
http GET "$ORDER_URL/orders/$ORDER_NO"
jget "d['status']"; ORDER_STATUS="$VALUE"
assert_eq "$ORDER_STATUS" "PARTIALLY_REFUNDED" "订单状态 → PARTIALLY_REFUNDED（7000/9900 已退）"
http GET "$DEMO_URL/demo/trace?orderId=$ORDER_NO"
jget "any(s['table']=='transaction_refunds' and s['rows'] for s in d['sections'])"; HAS_TXRF_ROWS="$VALUE"
assert_eq "$HAS_TXRF_ROWS" "True" "demo 追踪含 transaction_refunds（TXRF）段落且有数据"

echo ""
info "scenario-refund 全部断言通过 ✅"
