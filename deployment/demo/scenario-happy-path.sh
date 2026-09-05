#!/usr/bin/env bash
# demo/scenario-happy-path.sh —— 主链演示：建单 → 收银台回调（验签）→ 履约 → 权益 → 记账
# 前置：bash demo/reset.sh 已执行；服务经 deployment/start-all.sh 启动（mock-cashier 已开启）。
# 断言：payment SUCCEEDED；entitlement AVAILABLE；ledger balanced 且能按 PAYMENT/{paymentNo} 追溯分录。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

echo "==> ① 解析种子 SKU（DEMO-SKU-101）真实 id"
http GET "$CATALOG_URL/skus"
assert_status 200 "SKU 列表"
SKU_ID="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);m=[x for x in d if x.get('skuCode')=='DEMO-SKU-101'];print(m[0]['id'] if m else '')")"
[ -n "$SKU_ID" ] || fail "未找到种子 SKU DEMO-SKU-101（请先 bash demo/reset.sh）"
info "SKU_ID=$SKU_ID"

echo "==> ① 下单（SKU $SKU_ID × 1）—— Feature 015 起下单不再同步建支付单"
http POST "$ORDER_URL/orders" "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
assert_status 201 "下单"
jget "d['orderNo']"; ORDER_NO="$VALUE"
[ -n "$ORDER_NO" ] || fail "下单响应缺少 orderNo"
info "orderNo=$ORDER_NO"

echo "==> ①b 显式选渠道建支付单（一订单可多支付单，ADR-0064）"
http POST "$ORDER_URL/orders/$ORDER_NO/payments" '{"channelCode":"alipay"}'
assert_status 201 "选渠道建支付单"
jget "d['paymentNo']";     PAYMENT_NO="$VALUE"
jget "d['payUrl']";        PAY_URL="$VALUE"
jget "d['status']";        PAY_STATUS="$VALUE"
[ -n "$PAYMENT_NO" ] || fail "建支付单响应缺少 paymentNo"
info "paymentNo=$PAYMENT_NO status=$PAY_STATUS"
[ -n "$PAY_URL" ] && assert_contains "$PAY_URL" "/cashier?paymentNo=" "payUrl 携带业务单号（ADR-0063）" \
  || warn "payUrl 为空（mock-cashier 未开启：PAYMENT_MOCK_CASHIER_ENABLED=true）"

echo "==> ② 以渠道身份发签名回调（经 mock-channel-web 代理，HMAC-SHA256）"
http POST "$DEMO_URL/mock-channel/callback" \
  "{\"paymentNo\":\"$PAYMENT_NO\",\"status\":\"SUCCESS\",\"channelReference\":\"demo-ref-$ORDER_NO\",\"amountMinor\":9900,\"signMode\":\"VALID\"}"
assert_status 200 "渠道回调受理"
# 上游响应体在 body 字段内：{"upstreamStatus":200,"body":"{...payment json...}"}
PAY_STATUS="$(echo "$BODY" | python -c "
import json,sys
d=json.load(sys.stdin)
p=json.loads(d['body'])
print(p.get('status',''))
")"
assert_eq "$PAY_STATUS" "SUCCEEDED" "回调后支付状态 → SUCCEEDED"

echo "==> ③ 重复回调被幂等吸收（ADR-0025 占位：验签不拦截；业务层幂等键吸收第二次回调）"
http POST "$DEMO_URL/mock-channel/callback" \
  "{\"paymentNo\":\"$PAYMENT_NO\",\"status\":\"SUCCESS\",\"channelReference\":\"dup-$ORDER_NO\",\"amountMinor\":9900,\"signMode\":\"VALID\"}"
assert_status 200 "重复成功回调仍受理"
PAY_STATUS2="$(echo "$BODY" | python -c "
import json,sys
d=json.load(sys.stdin)
p=json.loads(d['body'])
print(p.get('status',''))
")"
assert_eq "$PAY_STATUS2" "SUCCEEDED" "重复回调后仍为 SUCCEEDED（幂等吸收，分录与履约不重复）"

echo "==> ③b 验证明文回调（ADR-0025 占位：验签尚未接入，伪造签名同样放行 —— 已知风险见 runbook §6）"
http POST "$DEMO_URL/mock-channel/callback" \
  "{\"paymentNo\":\"$PAYMENT_NO\",\"status\":\"SUCCESS\",\"channelReference\":\"forged-$ORDER_NO\",\"amountMinor\":9900,\"signMode\":\"FORGED\"}"
assert_status 200 "占位形态下伪造签名回调同样被放行（验签未接入）"

echo "==> ④ 权益已发放"
http GET "$ENTITLEMENT_URL/entitlements/by-order/$ORDER_NO"
assert_status 200 "权益按订单可查"
jget "len(d)";         ENT_COUNT="$VALUE"
jget "d[0]['status']"; ENT_STATUS="$VALUE"
assert_eq "$ENT_STATUS" "AVAILABLE" "权益状态 AVAILABLE"
assert_eq "$ENT_COUNT" "1" "权益只发放一份（履约恰好一次）"

echo "==> ⑤ 记账平衡且分录可追溯"
http GET "$LEDGER_URL/internal/ledger/balance"
assert_status 200 "余额视图"
jget "d['balanced']"; BALANCED="$VALUE"
assert_eq "$BALANCED" "True" "ledger 复式记账 balanced"
http GET "$LEDGER_URL/internal/ledger/entries?sourceType=PAYMENT&sourceId=$PAYMENT_NO"
assert_status 200 "按 PAYMENT/{paymentNo} 追溯分录"
jget "len(d)"; ENTRY_COUNT="$VALUE"
if [ "$ENTRY_COUNT" -ge 1 ] 2>/dev/null; then
  info "PASS: 支付分录共 $ENTRY_COUNT 条"
else
  fail "支付分录为空（$ENTRY_COUNT 条）"
fi

echo ""
info "scenario-happy-path 全部断言通过 ✅"
