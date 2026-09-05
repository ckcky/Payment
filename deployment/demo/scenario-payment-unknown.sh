#!/usr/bin/env bash
# demo/scenario-payment-unknown.sh —— UNKNOWN 权威收敛演示
# 前置：服务已启动；本场景要求 payment-service 以 mock-scenario=BUSINESS_UNKNOWN 运行：
#       bash demo/restart-payment.sh BUSINESS_UNKNOWN
# 断言：支付进入 UNKNOWN（不猜成败落账）→ 无令牌 resolve 被 403 → 带令牌 resolve 收敛为 FAILED（终态）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

echo "==> ① 解析种子 SKU（DEMO-SKU-101）"
http GET "$CATALOG_URL/skus"
assert_status 200 "SKU 列表"
SKU_ID="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);m=[x for x in d if x.get('skuCode')=='DEMO-SKU-101'];print(m[0]['id'] if m else '')")"
[ -n "$SKU_ID" ] || fail "未找到种子 SKU DEMO-SKU-101（请先 bash demo/reset.sh）"

echo "==> ② 下单 + 显式选渠道建支付单（Feature 015 两步式；BUSINESS_UNKNOWN 场景 → 支付落 UNKNOWN）"
http POST "$ORDER_URL/orders" "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
assert_status 201 "下单"
jget "d['orderNo']"; ORDER_NO="$VALUE"
[ -n "$ORDER_NO" ] || fail "下单响应缺少 orderNo"
http POST "$ORDER_URL/orders/$ORDER_NO/payments" '{"channelCode":"alipay"}'
assert_status 201 "选渠道建支付单"
jget "d['paymentNo']"; PAYMENT_NO="$VALUE"
[ -n "$PAYMENT_NO" ] || fail "建支付单响应缺少 paymentNo"
info "orderNo=$ORDER_NO paymentNo=$PAYMENT_NO"

echo "==> ③ 等待支付进入 UNKNOWN（不猜成败落账）"
wait_until 60 3 "payment 进入 UNKNOWN" bash -c "curl -s --noproxy '*' $PAYMENT_URL/payments/$PAYMENT_NO | python -c \"import json,sys;print('UNKNOWN' if json.load(sys.stdin).get('status')=='UNKNOWN' else 'WAIT')\" | grep -q UNKNOWN"
# wait_until 的探测不更新 BODY，需重新拉取支付单
http GET "$PAYMENT_URL/payments/$PAYMENT_NO"
jget "d['status']"; STATUS1="$VALUE"
assert_eq "$STATUS1" "UNKNOWN" "支付状态 → UNKNOWN（渠道无明确结论，不猜成败落账）"

echo "==> ④ 无令牌 resolve 被 403 拒绝（resolve 端点鉴权开关 auth-enabled=true）"
http POST "$PAYMENT_URL/payments/$PAYMENT_NO/resolve" "{\"result\":\"FAILURE\",\"reason\":\"demo-no-token\"}"
assert_status 403 "无 X-Admin-Token 的 resolve 被拒（403）"

echo "==> ⑤ 带令牌 resolve 权威收敛 → FAILED（终态，只触发一次履约侧处理）"
http POST "$PAYMENT_URL/payments/$PAYMENT_NO/resolve" "{\"result\":\"FAILURE\",\"reason\":\"demo-authoritative\"}" "X-Admin-Token: $ADMIN_TOKEN"
assert_status 200 "人工收敛受理"
jget "d['status']"; STATUS2="$VALUE"
assert_eq "$STATUS2" "FAILED" "人工裁定 → FAILED（权威收敛，终态）"

echo ""
info "scenario-payment-unknown 全部断言通过 ✅"
