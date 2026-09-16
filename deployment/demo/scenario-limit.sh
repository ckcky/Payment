#!/usr/bin/env bash
# demo/scenario-limit.sh —— 用户支付限额演示（spec 027 / ADR-0071）
#
# 前置：服务已启动（start-all.sh）；mock 收银台关闭（`bash demo/set-cashier.sh off`）——
#       本脚本需要渠道回调同步返回 SUCCESS/FAILED，才能观察到 pending → used 的流转。
#
# **断言口径（与 acceptance.md 一致）**：
#   1. 超限一律读 **HTTP 状态码 409 + code=LIMIT_EXCEEDED**，不匹配错误消息文本；
#   2. 额度占用一律读 `GET /internal/limits/users/{userId}` 的 `periods[].used /
#      periods[].pending`（单位：分）—— 它是**唯一权威口径**（DB），不是 Redis、也不是推算；
#   3. 「未创建」的验证读 `payments` 表有无新行：超限时建单事务整体回滚（INV-3），
#      端点返回的 paymentNo 根本不该存在。
#
# 七个场景（对齐 spec 027 acceptance.md SC-001 ~ SC-016 的实跑子集）：
#   L1 无配置 = 不限额          → 建单放行，`payments` 有行（SC-010 兼容性基线）
#   L2 额度内预占               → pending 上升、used 不变（FR-006）
#   L3 超限拒绝且未创建          → 409 + LIMIT_EXCEEDED，`payments` 无新行（FR-019 / INV-3）
#   L4 三周期任一超限即整笔拒绝   → 日额度充足、月额度不足 → 409，且日周期不留残留（FR-010）
#   L5 支付成功后 pending → used → confirm 后 used 累加、pending 归零（FR-013）
#   L6 支付失败后释放            → pending 归零、used 不变（FR-014）
#   L7 幂等：同单号重放不重复累加  → 重复回调后 used 仍是单倍（INV-4 / SC-006）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

LIMIT_USER="limit-demo-user"
LIMIT_URL="$PAYMENT_URL/internal/limits/users/$LIMIT_USER"
AMOUNT=9900

# ---------------------------------------------------------------------------
# 端点封装
# ---------------------------------------------------------------------------
# 设置限额（PUT 是幂等 upsert）。0 = 该周期不限。
set_limit() { # set_limit <dailyMinor> <monthlyMinor> <yearlyMinor> [status]
  local daily="$1" monthly="$2" yearly="$3" status="${4:-ACTIVE}"
  http PUT "$LIMIT_URL" "{\"currencyCode\":\"CNY\",\"dailyLimitMinor\":$daily,\"monthlyLimitMinor\":$monthly,\"yearlyLimitMinor\":$yearly,\"status\":\"$status\"}"
}

# 清空限额配置（= 回归不限额，FR-025 / SC-010）
clear_limit() {
  http DELETE "$LIMIT_URL"
}

# 读某周期的占用（权威口径：DB）。
# 只向 stdout 输出数值，其余（http 的调用日志）一律重定向到 stderr——
# 否则 `$(occupied_of ...)` 会把日志行一起捕获进变量，断言必然失败（spec 028 实测踩坑）。
occupied_of() { # occupied_of <DAY|MONTH|YEAR> <used|pending>
  local period="$1" field="$2"
  http GET "$LIMIT_URL" >&2 || true
  echo "$BODY" | python -c "
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print('0'); sys.exit(0)
field='$field'
for p in (d.get('periods') or []):
    if str(p.get('period'))=='$period':
        print(p.get(field, 0)); sys.exit(0)
print(0)
" 2>/dev/null
}

# 建单 + 建支付单（spec 028 后：不传 channelCode 由平台选路，避免依赖具体渠道配置）
# 结果：ORDER_NO / PAYMENT_NO / PAY_STATUS
create_order_and_pay() {
  http POST "$ORDER_URL/orders" "{\"userId\":\"$LIMIT_USER\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
  assert_status 201 "下单"
  jget "d['orderNo']"; ORDER_NO="$VALUE"
  [ -n "$ORDER_NO" ] || fail "下单响应缺少 orderNo"
  http POST "$ORDER_URL/orders/$ORDER_NO/payments" '{}'
  jget "d['paymentNo']"; PAYMENT_NO="$VALUE"
  jget "d['status']";    PAY_STATUS="$VALUE"
}

# 该订单在 payments 表有几行（经 demo trace 按 orderNo 查库；权威事实源）。
# 注意：/demo/trace 的唯一入参是 orderId（支持 orderNo 或历史数值 id），**不接受 paymentNo**。
payment_row_count() { # payment_row_count <orderNo>
  local on="$1"
  [ -n "$on" ] || { echo 0; return 0; }
  http GET "$DEMO_URL/demo/trace?orderId=$on" >&2 || true
  echo "$BODY" | python -c "
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
for s in d.get('sections',[]):
    if s.get('table')=='payments':
        print(len(s.get('rows') or [])); sys.exit(0)
print(0)
" 2>/dev/null
}

# 代渠道补发成功回调（同步结算 pending → used）
settle_success() { # settle_success <paymentNo>
  http POST "$DEMO_URL/mock-channel/callback" \
    "{\"paymentNo\":\"$1\",\"status\":\"SUCCESS\",\"channelReference\":\"limit-demo-$1\",\"amountMinor\":$AMOUNT,\"signMode\":\"VALID\"}"
  assert_status 200 "渠道成功回调受理"
}

# 代渠道补发失败回调（释放 pending）
settle_failure() { # settle_failure <paymentNo>
  http POST "$DEMO_URL/mock-channel/callback" \
    "{\"paymentNo\":\"$1\",\"status\":\"FAILED\",\"channelReference\":\"limit-demo-f-$1\",\"amountMinor\":$AMOUNT,\"signMode\":\"VALID\"}"
  assert_status 200 "渠道失败回调受理"
}

echo "==> ⓪ 前置检查"
http GET "$CATALOG_URL/skus"
assert_status 200 "SKU 列表"
SKU_ID="$(echo "$BODY" | python -c "import json,sys;d=json.load(sys.stdin);m=[x for x in d if x.get('skuCode')=='DEMO-SKU-101'];print(m[0]['id'] if m else '')")"
[ -n "$SKU_ID" ] || fail "未找到种子 SKU DEMO-SKU-101（请先 bash demo/reset.sh）"

# 起点归一：清掉上轮遗留的配置（每轮从「不限额」这个确定起点开始）
echo "==> ⓪a 清理上轮遗留的限额配置（含排障期的手工配置）"
clear_limit
http GET "$LIMIT_URL"
assert_status 200 "限额快照可读"
jget "d['config']['configured']"; assert_eq "$VALUE" "False" "起点：该用户无限额配置（= 不限额）"

echo "==> L1 无配置 = 不限额 → 建单放行（SC-010 兼容性基线）"
create_order_and_pay
assert_status 201 "L1 建支付单（无限额配置）"
L1_PAYMENT_NO="$PAYMENT_NO"
[ -n "$L1_PAYMENT_NO" ] || fail "L1 应建单成功，但响应缺少 paymentNo"
assert_eq "$(payment_row_count "$ORDER_NO")" "1" "L1 payments 表确有新行（不限额放行）"
settle_success "$L1_PAYMENT_NO"

echo "==> L2 配置日额度 → 额度内预占：pending 上升"
DAY_LIMIT=$((AMOUNT * 3))     # 日额度只够 3 笔
set_limit "$DAY_LIMIT" 0 0
assert_status 200 "PUT 日额度 $DAY_LIMIT"
# 上一笔已 settle 成 used，先读基线
USED_BASE="$(occupied_of DAY used)"
PENDING_BASE="$(occupied_of DAY pending)"
info "L2 基线：used=$USED_BASE pending=$PENDING_BASE（上一笔已确认，used 应已含它）"
[ "$USED_BASE" -ge "$AMOUNT" ] || fail "L2 上一笔确认后 used 应 >= $AMOUNT，实际 $USED_BASE"

create_order_and_pay
assert_status 201 "L2 额度内建支付单"
L2_PAYMENT_NO="$PAYMENT_NO"
assert_eq "$(occupied_of DAY used)" "$USED_BASE" "L2 used 不因预占而变（FR-006）"
assert_eq "$(occupied_of DAY pending)" "$((PENDING_BASE + AMOUNT))" "L2 pending 增加一笔（在途占用）"

echo "==> L3 用尽额度后超限 → 409 + LIMIT_EXCEEDED 且 payments 无新行（FR-019 / INV-3）"
# 把日额度压到「已用满」：used=9900, pending=9900, limit 只剩 0 可用
set_limit "$((USED_BASE + PENDING_BASE))" 0 0
assert_status 200 "PUT 日额度收窄到当前占用"
http POST "$ORDER_URL/orders" "{\"userId\":\"$LIMIT_USER\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
assert_status 201 "L3 下单（下单不受限额影响）"
L3_ORDER_NO="$ORDER_NO"
http POST "$ORDER_URL/orders/$L3_ORDER_NO/payments" '{}'
assert_status 409 "L3 建支付单被拒（409）"
jget "d['code']"; assert_eq "$VALUE" "LIMIT_EXCEEDED" "L3 错误码为 LIMIT_EXCEEDED"
VALUE_MSG="$(echo "$BODY" | python -c "import json,sys;print(json.load(sys.stdin).get('message',''))" 2>/dev/null || true)"
assert_contains "$VALUE_MSG" "exceeded" "L3 错误消息说明哪个周期超限（可执行错误）"
info "L3 错误消息：$VALUE_MSG"
# INV-3：超限是「未创建」——该订单不该在 payments 表留下任何行
assert_eq "$(payment_row_count "$L3_ORDER_NO")" "0" "L3 超限时 payments 表无该订单的行（未创建而非创建了再拒）"
jget "d['paymentNo']"; L3_PAYMENT_NO="$VALUE"
if [ -n "$L3_PAYMENT_NO" ]; then
  info "注意：L3 响应意外携带 paymentNo=$L3_PAYMENT_NO（若 payments 无行则仍属未创建）"
else
  info "PASS: L3 响应未携带 paymentNo（建单事务已整体回滚）"
fi

echo "==> L4 三周期任一超限即整笔拒绝，且已预占周期不留残留（FR-010）"
# 日额度放宽（不构成短板）、月额度设成「已用满」
clear_limit
set_limit 100000000 0 0
assert_status 200 "PUT 日额度极大（非短板）"
create_order_and_pay
assert_status 201 "L4 先用掉一笔，制造月周期占用"
L4_SEED_PAYMENT="$PAYMENT_NO"
settle_success "$L4_SEED_PAYMENT"
USED_DAY="$(occupied_of DAY used)"
USED_MONTH="$(occupied_of MONTH used)"
info "L4 基线：DAY used=$USED_DAY / MONTH used=$USED_MONTH"
# 月额度 = 月已用（放不下新的一笔），日额度仍有极大余量
set_limit 100000000 "$USED_MONTH" 0
assert_status 200 "PUT 月额度收窄（制造 MONTH 短板、DAY 富余）"
PENDING_DAY_BEFORE="$(occupied_of DAY pending)"
create_order_and_pay
assert_status 409 "L4 月周期超限被拒（409）"
jget "d['code']"; assert_eq "$VALUE" "LIMIT_EXCEEDED" "L4 错误码为 LIMIT_EXCEEDED"
VALUE_MSG="$(echo "$BODY" | python -c "import json,sys;print(json.load(sys.stdin).get('message',''))" 2>/dev/null || true)"
assert_contains "$VALUE_MSG" "MONTH" "L4 报出的正是短板周期 MONTH"
assert_eq "$(occupied_of DAY pending)" "$PENDING_DAY_BEFORE" "L4 日周期预占已回滚，不得部分扣减（FR-010）"

echo "==> L5 支付成功：pending → used（FR-013）"
clear_limit
set_limit 100000000 0 0
create_order_and_pay
assert_status 201 "L5 建支付单"
L5_PAYMENT_NO="$PAYMENT_NO"
USED_BEFORE="$(occupied_of DAY used)"
PENDING_BEFORE="$(occupied_of DAY pending)"
assert_eq "$PENDING_BEFORE" "$AMOUNT" "L5 预占后 pending = 单笔金额"
settle_success "$L5_PAYMENT_NO"
assert_eq "$(occupied_of DAY used)" "$((USED_BEFORE + AMOUNT))" "L5 确认后 used 增加一笔"
assert_eq "$(occupied_of DAY pending)" "0" "L5 确认后 pending 归零（在途转已确认）"

echo "==> L6 支付失败：pending 释放、used 不变（FR-014）"
create_order_and_pay
assert_status 201 "L6 建支付单"
L6_PAYMENT_NO="$PAYMENT_NO"
USED_BEFORE="$(occupied_of DAY used)"
assert_eq "$(occupied_of DAY pending)" "$AMOUNT" "L6 预占后 pending = 单笔金额"
settle_failure "$L6_PAYMENT_NO"
assert_eq "$(occupied_of DAY pending)" "0" "L6 失败后 pending 释放归零"
assert_eq "$(occupied_of DAY used)" "$USED_BEFORE" "L6 used 不受失败影响（未确认不记账）"

echo "==> L7 幂等：重复回调不重复累加（INV-4 / SC-006）"
create_order_and_pay
assert_status 201 "L7 建支付单"
L7_PAYMENT_NO="$PAYMENT_NO"
settle_success "$L7_PAYMENT_NO"
USED_AFTER_FIRST="$(occupied_of DAY used)"
info "L7 首次确认后 used=$USED_AFTER_FIRST"
# 重放同一笔成功回调（渠道重试 / 运维补发）
settle_success "$L7_PAYMENT_NO"
assert_eq "$(occupied_of DAY used)" "$USED_AFTER_FIRST" "L7 重复确认后 used 不变（幂等流水挡住二次累加）"
# 流水侧证：同一单号的 CONFIRM 流水只有一条
http GET "$LIMIT_URL/payments/$L7_PAYMENT_NO/operations"
assert_status 200 "L7 额度流水可查"
CONFIRM_COUNT="$(echo "$BODY" | python -c "
import json,sys
d=json.load(sys.stdin)
print(sum(1 for o in (d.get('operations') or []) if o.get('opType')=='CONFIRM'))
" 2>/dev/null)"
assert_eq "$CONFIRM_COUNT" "1" "L7 同单号 CONFIRM 流水恰好一条"

echo "==> ⓪b 收尾：清理限额配置（避免污染后续场景）"
clear_limit
http GET "$LIMIT_URL"
assert_status 200 "限额快照可读"
jget "d['config']['configured']"; assert_eq "$VALUE" "False" "收尾：配置已删除（回归不限额，SC-010）"

info "==== 限额演示全部通过 ===="
info "L1 不限额放行 / L2 额度内预占 / L3 超限 409 且未创建 / L4 三周期整笔拒绝"
info "L5 成功转已确认 / L6 失败释放 / L7 幂等不重复累加"
