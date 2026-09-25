#!/usr/bin/env bash
# demo/scenario-routing.sh —— 渠道路由演示（spec 028 / ADR-0072、ADR-0073）
#
# 前置：服务已启动（start-all.sh）；mock 收银台开启（默认）。
#
# **断言口径（与 acceptance.md 一致）**：渠道归属一律读 `payment_attempts.channel_code`
# **列**，不以渠道引用的字符串形态作为验收标准。引用前缀只承载排障可读性。
# （注意：`payments` 表**没有** channel_code 列——渠道身份记在尝试行上。）
#
# 六个场景（对齐 spec 028 §5.3）：
#   S1 只表达支付意图        → 落 priority 最小的 enabled 渠道（ALIPAY）
#   S2 显式指定优先          → WECHAT（Router 零干预）
#   S3 自动避开停用渠道      → ALIPAY=DOWN 后自动落 WECHAT
#   S4 明确拒绝不偷改        → 显式 ALIPAY + DOWN → 409，且不落 payment_attempt
#   S5 两渠道是独立实现实例  → 显式指定各自落在各自渠道
#   S6 退款回原渠道          → 对 S2 的 WECHAT 支付退款，退款 attempt 仍为 WECHAT（INV-6）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

# ---- 演示开关（FR-034 尾注）：仅 demo profile 注册；重启即回到配置值 ----
set_channel_status() { # set_channel_status <CODE> <UP|DEGRADED|DOWN>
  http POST "$PAYMENT_URL/internal/channels/$1/status" "{\"status\":\"$2\"}"
  assert_status 200 "渠道 $1 → $2"
}

# 读 payment_attempts.channel_code 列（权威口径），按 attempt_type 过滤取最新一条。
# 经 /demo/trace?orderId=<orderNo> 查库（该端点已暴露 payment_attempts 全列）。
#
# 注意：本函数**只向 stdout 输出渠道码**，其余一切（含 http() 的调用日志）一律重定向到
# stderr —— 否则 `$(attempt_channel_of ...)` 会把日志行一并捕获进变量，断言必然失败。
attempt_channel_of() { # attempt_channel_of <orderNo> <PAYMENT|REFUND> [paymentNo]
  local order_no="$1" type="$2" want_pn="${3:-}"
  http GET "$DEMO_URL/demo/trace?orderId=$order_no" >&2 || true
  echo "$BODY" | python -c "
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
rows=[]
for s in d.get('sections',[]):
    if s.get('table')=='payment_attempts':
        rows=s.get('rows') or []
want='$type'; pn='$want_pn'
hits=[r for r in rows if str(r.get('attempt_type'))==want and (not pn or str(r.get('payment_no'))==pn)]
hits.sort(key=lambda r: r.get('id') or 0)
print(hits[-1].get('channel_code','') if hits else '')
" 2>/dev/null
}

# 建单 + 建支付单：不传渠道 = 只表达支付意图（US2）；传渠道 = 显式指定（US3）。
# 结果：ORDER_NO / PAYMENT_NO。返回非 0 = 建支付单失败（调用方自行断言状态码）。
create_order_and_pay() { # create_order_and_pay <skuId> [channelCode]
  local sku="$1" code="${2:-}"
  http POST "$ORDER_URL/orders" "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$sku,\"quantity\":1}]}"
  assert_status 201 "下单"
  jget "d['orderNo']"; ORDER_NO="$VALUE"
  [ -n "$ORDER_NO" ] || fail "下单响应缺少 orderNo"
  if [ -n "$code" ]; then
    http POST "$ORDER_URL/orders/$ORDER_NO/payments" "{\"channelCode\":\"$code\"}"
  else
    # 只表达支付意图：不传 channelCode，由平台选路（US2 主路径）
    http POST "$ORDER_URL/orders/$ORDER_NO/payments" '{}'
  fi
  jget "d['paymentNo']"; PAYMENT_NO="$VALUE"
  jget "d['status']";    PAY_STATUS="$VALUE"
}

# cashier 路径代渠道补发回调（mock-cashier 开启时支付停在 PROCESSING）
settle_pending() { # settle_pending <paymentNo> <amountMinor>
  [ -n "$1" ] || return 0
  http GET "$PAYMENT_URL/payments?paymentNo=$1"
  jget "d['status']"; local st="$VALUE"
  if [ "$st" = "PROCESSING" ]; then
    info "cashier 路径（PROCESSING），代渠道补发签名回调"
    http POST "$DEMO_URL/mock-channel/callback" \
      "{\"paymentNo\":\"$1\",\"status\":\"SUCCESS\",\"channelReference\":\"route-demo-$1\",\"amountMinor\":$2,\"signMode\":\"VALID\"}"
    assert_status 200 "渠道回调受理"
  fi
}

echo "==> ⓪ 解析种子 SKU"
http GET "$CATALOG_URL/skus"
assert_status 200 "SKU 列表"
SKU_ID="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);m=[x for x in d if x.get('skuCode')=='DEMO-SKU-101'];print(m[0]['id'] if m else '')")"
[ -n "$SKU_ID" ] || fail "未找到种子 SKU DEMO-SKU-101（请先 bash demo/reset.sh）"
AMOUNT=9900

# ⓪a 复位渠道可用性（**必须**）。
# POST /internal/channels/{code}/status 是纯内存覆盖，重启才失效；demo/reset.sh 只重建数据库，
# 不会碰它。上次运行若把 ALIPAY 置 DOWN 且中途失败，本轮的 auto 选路起点就不是配置值，
# 断言会在最开头误报（2026-09-08 实测踩坑）。故每个渠道都显式置回 UP，让场景从确定起点开始。
echo "==> ⓪a 复位渠道可用性（清掉上轮遗留的内存覆盖）"
for code in ALIPAY WECHAT DOUYIN MOCK; do
  set_channel_status "$code" UP
done

echo "==> ⓪b 路由快照（FR-044 / FR-045）"
http GET "$PAYMENT_URL/internal/channels"
assert_status 200 "渠道清单"
jget "[c['code'] for c in d['channels']]"; REGISTERED="$VALUE"
assert_contains "$REGISTERED" "ALIPAY" "已注册 ALIPAY"
assert_contains "$REGISTERED" "WECHAT" "已注册 WECHAT"
assert_contains "$REGISTERED" "DOUYIN" "已注册 DOUYIN"
http GET "$PAYMENT_URL/internal/channels/route-preview"
assert_status 200 "路由预览（dry-run，不落库）"
jget "d['wouldRouteTo']"; EXPECT_AUTO="$VALUE"
assert_eq "$EXPECT_AUTO" "ALIPAY" "当前配置下 auto 选 ALIPAY（enabled 且 priority 最小）"
jget "d['orderedCandidates'][0]"; TOP="$VALUE"
assert_eq "$TOP" "ALIPAY" "候选排序首位 == ALIPAY"

echo "==> S1 只表达支付意图 → 自动落 ALIPAY"
create_order_and_pay "$SKU_ID"
assert_status 201 "S1 建支付单（不传渠道）"
ORDER_NO_S1="$ORDER_NO"; PAYMENT_NO_S1="$PAYMENT_NO"
settle_pending "$PAYMENT_NO_S1" "$AMOUNT"
AT1="$(attempt_channel_of "$ORDER_NO_S1" PAYMENT "$PAYMENT_NO_S1")"
assert_eq "$AT1" "ALIPAY" "S1 payment_attempts.channel_code == ALIPAY（auto 选路生效）"

echo "==> S2 显式指定 WECHAT → Router 零干预"
create_order_and_pay "$SKU_ID" "WECHAT"
assert_status 201 "S2 建支付单（显式 WECHAT）"
ORDER_NO_S2="$ORDER_NO"; PAYMENT_NO_S2="$PAYMENT_NO"
AT2="$(attempt_channel_of "$ORDER_NO_S2" PAYMENT "$PAYMENT_NO_S2")"
assert_eq "$AT2" "WECHAT" "S2 payment_attempts.channel_code == WECHAT（显式优先，未被 auto 覆盖）"
settle_pending "$PAYMENT_NO_S2" "$AMOUNT"

echo "==> S3 自动避开停用渠道（ALIPAY=DOWN）→ 自动落 WECHAT"
set_channel_status ALIPAY DOWN
http GET "$PAYMENT_URL/internal/channels/route-preview"
jget "d['wouldRouteTo']"; AUTO_AFTER_DOWN="$VALUE"
assert_eq "$AUTO_AFTER_DOWN" "WECHAT" "ALIPAY=DOWN 后 auto 改选 WECHAT（DOWN 排除出候选集）"
jget "[e['code'] for e in d['excluded']]"; EXCL="$VALUE"
assert_contains "$EXCL" "ALIPAY" "预览的 excluded 含 ALIPAY"
create_order_and_pay "$SKU_ID"
assert_status 201 "S3 建支付单（不传渠道，ALIPAY 已 DOWN）"
ORDER_NO_S3="$ORDER_NO"; PAYMENT_NO_S3="$PAYMENT_NO"
AT3="$(attempt_channel_of "$ORDER_NO_S3" PAYMENT "$PAYMENT_NO_S3")"
assert_eq "$AT3" "WECHAT" "S3 payment_attempts.channel_code == WECHAT（自动绕开 DOWN 渠道）"
settle_pending "$PAYMENT_NO_S3" "$AMOUNT"

echo "==> S4 显式指定 DOWN 渠道 → 409 CHANNEL_UNAVAILABLE，且不落 payment_attempt"
http POST "$ORDER_URL/orders" "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
assert_status 201 "S4 下单"
jget "d['orderNo']"; ORDER_NO_S4="$VALUE"
http POST "$ORDER_URL/orders/$ORDER_NO_S4/payments" '{"channelCode":"ALIPAY"}'
assert_status 409 "S4 显式指定 ALIPAY（DOWN）→ 409（明确拒绝，不偷改）"
http GET "$DEMO_URL/demo/trace?orderId=$ORDER_NO_S4" || true
HAS_ATTEMPT="$(echo "$BODY" | python -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print('False'); sys.exit(0)
print(any(s.get('table')=='payment_attempts' and s.get('rows') for s in d.get('sections',[])))
" 2>/dev/null)"
assert_eq "$HAS_ATTEMPT" "False" "S4 未落 payment_attempt（拒绝发生在选路阶段，无部分写入）"

# 恢复 ALIPAY，避免影响后续场景
set_channel_status ALIPAY UP

echo "==> S5 两渠道是各自独立的实现实例（同一订单号段下分别落各自渠道）"
create_order_and_pay "$SKU_ID" "ALIPAY"
assert_status 201 "S5 建支付单 A（ALIPAY）"
ORDER_NO_S5A="$ORDER_NO"; PAYMENT_NO_S5A="$PAYMENT_NO"
settle_pending "$PAYMENT_NO_S5A" "$AMOUNT"
create_order_and_pay "$SKU_ID" "WECHAT"
assert_status 201 "S5 建支付单 B（WECHAT）"
ORDER_NO_S5B="$ORDER_NO"; PAYMENT_NO_S5B="$PAYMENT_NO"
settle_pending "$PAYMENT_NO_S5B" "$AMOUNT"
AT5A="$(attempt_channel_of "$ORDER_NO_S5A" PAYMENT "$PAYMENT_NO_S5A")"
AT5B="$(attempt_channel_of "$ORDER_NO_S5B" PAYMENT "$PAYMENT_NO_S5B")"
assert_eq "$AT5A" "ALIPAY" "S5 第一笔落在 ALIPAY"
assert_eq "$AT5B" "WECHAT" "S5 第二笔落在 WECHAT（两渠道是独立实现实例，非同一单例）"

echo "==> S6 退款回原渠道（INV-6）：对 S2 的 WECHAT 支付发起退款"
http POST "$ORDER_URL/internal/orders/refund" \
  "{\"orderNo\":\"$ORDER_NO_S2\",\"amountMinor\":3000,\"reason\":\"demo-routing-refund\"}"
assert_status 200 "退款受理"
jget "d['pmrf']"; PMRF="$VALUE"
jget "d['status']"; REFUND_STATUS="$VALUE"
[ -n "$PMRF" ] || fail "退款响应缺失 pmrf"

# 等异步回调收敛到终态（UNKNOWN 是渠道在途中间态，不作退出条件）
for i in $(seq 1 30); do
  http GET "$PAYMENT_URL/internal/payments/refunds/$PMRF" || true
  jget "d['status']"; REFUND_STATUS="$VALUE"
  case "$REFUND_STATUS" in SUCCEEDED|FAILED) break ;; esac
  sleep 0.2
done
assert_eq "$REFUND_STATUS" "SUCCEEDED" "退款收敛 SUCCEEDED（PMRF=${PMRF}）"
AT6="$(attempt_channel_of "$ORDER_NO_S2" REFUND "$PAYMENT_NO_S2")"
assert_eq "$AT6" "WECHAT" "S6 退款 attempt.channel_code == WECHAT（回原渠道，不因 ALIPAY 优先级更高而改道）"

echo "==> ⓪c 路由计数快照（FR-041）"
http GET "$PAYMENT_URL/actuator/prometheus"
if echo "$BODY" | grep -q "payment_routing_total"; then
  info "PASS: payment_routing_total 已暴露"
  echo "$BODY" | grep "^payment_routing_total" | head -8
else
  fail "payment_routing_total 未在 /actuator/prometheus 暴露"
fi

echo ""
info "scenario-routing 全部断言通过 ✅（S1~S6）"
