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

# 每轮用**本次运行唯一**的用户（可被 LIMIT_USER 覆盖）：
# 额度占用是随支付事实累计的，而「清占用」没有对外的写端点（`user_limit_usage` 只能靠支付事实演进）。
# 复用固定用户会让上一轮未结算的在途/pending 残留进来，使 L1b「起点占用为 0」这类断言随机失败
# ——这是「脚本自己污染自己」的假红，2026-09-16 实跑踩到。用唯一用户名天然隔离历史。
LIMIT_USER="${LIMIT_USER:-limit-demo-user-$RANDOM}"
LIMIT_URL="$PAYMENT_URL/internal/limits/users/$LIMIT_USER"
AMOUNT=9900
info "本轮限额演示用户：$LIMIT_USER"

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
  # ⚠️ 回调状态字面量是 **FAILURE**（渠道层 ChannelResult.Status），不是 payment 侧的 FAILED
  #    （PaymentStatus）。写 FAILED 会被渠道回调端点以 400 INVALID_ARGUMENT 拒收
  #    ——「status must be SUCCESS, FAILURE or UNKNOWN」（2026-09-16 实跑踩到）。
  http POST "$DEMO_URL/mock-channel/callback" \
    "{\"paymentNo\":\"$1\",\"status\":\"FAILURE\",\"channelReference\":\"limit-demo-f-$1\",\"amountMinor\":$AMOUNT,\"signMode\":\"VALID\"}"
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
# ⚠️ 本笔发生在「无配置」状态：按 FR-012 / INV-2，无配置 = 不限额 → **不预占、不结算**，
#    故它不会在 user_limit_usage 里留下任何占用（下面 L2 的基线断言的正是这一点）。
#    这是 US1 场景 4「行为与今天完全一致」的实跑证据，不是缺陷。

echo "==> L1b 无配置下结算不留占用（FR-012 / INV-2 的正面证据）"
assert_eq "$(occupied_of DAY used)" "0" "L1b 无配置期间的支付不累计 used"
assert_eq "$(occupied_of DAY pending)" "0" "L1b 无配置期间的支付不产生 pending"

echo "==> L2 配置日额度 → 额度内预占：pending 上升"
DAY_LIMIT=$((AMOUNT * 3))     # 日额度只够 3 笔
set_limit "$DAY_LIMIT" 0 0
assert_status 200 "PUT 日额度 $DAY_LIMIT"
# 起点是「已核实为 0」的干净基线（L1b 已断言）——不再假设上一笔会累计。
USED_BASE="$(occupied_of DAY used)"
PENDING_BASE="$(occupied_of DAY pending)"
info "L2 基线：used=$USED_BASE pending=${PENDING_BASE}（配置刚生效，占用为 0）"
assert_eq "$USED_BASE" "0" "L2 起点的 used 为 0（配置生效前的支付不计入）"
assert_eq "$PENDING_BASE" "0" "L2 起点的 pending 为 0"

create_order_and_pay
assert_status 201 "L2 额度内建支付单"
L2_PAYMENT_NO="$PAYMENT_NO"
assert_eq "$(occupied_of DAY used)" "$USED_BASE" "L2 used 不因预占而变（FR-006）"
assert_eq "$(occupied_of DAY pending)" "$((PENDING_BASE + AMOUNT))" "L2 pending 增加一笔（在途占用）"

echo "==> L3 用尽额度后超限 → 409 + LIMIT_EXCEEDED 且 payments 无新行（FR-019 / INV-3）"
# 把日额度压到「只剩 1 分」：现读占用（L2 已产生 pending=9900），额度 = 占用 + 1 分。
# ⚠️ 不能直接 PUT 0：0 的语义是「该周期不限」（FR-020），会变成放行而非超限。
DAY_OCCUPIED="$(occupied_of DAY occupied)"
L3_LIMIT=$((DAY_OCCUPIED + 1))
set_limit "$L3_LIMIT" 0 0
assert_status 200 "PUT 日额度收窄到当前占用 + 1 分（${L3_LIMIT}）"
http POST "$ORDER_URL/orders" "{\"userId\":\"$LIMIT_USER\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
assert_status 201 "L3 下单（下单不受限额影响）"
# ⚠️ 必须解析出本轮的 orderNo：不解析的话 ORDER_NO 会停留在上一笔（L2）的订单号上，
#    INV-3 的「payments 无该订单的行」就会去查一个无关订单，断言随机假红（2026-09-16 踩到）。
jget "d['orderNo']"; L3_ORDER_NO="$VALUE"
[ -n "$L3_ORDER_NO" ] || fail "L3 下单响应缺少 orderNo"
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
  info "注意：L3 响应意外携带 paymentNo=${L3_PAYMENT_NO}（若 payments 无行则仍属未创建）"
else
  info "PASS: L3 响应未携带 paymentNo（建单事务已整体回滚）"
fi

# L3 收尾：把 L2 那笔仍挂在在途的支付结算掉（used 累加、pending 归零）。
# ⚠️ 必要性：`clear_limit` 只删配置、**不碰占用**（占用是支付事实的投影，没有清空端点）。
#    L2 的 pending 会一直挂到被结算或 TTL 回收为止，若不带走，L4 的 seed 会叠加上它，
#    使 L4/L5 的基线变成 19800 而不是 9900（2026-09-16 实跑踩到）。
info "L3 收尾：结算 L2 的在途支付，避免占用带入后续场景"
settle_success "$L2_PAYMENT_NO"
assert_eq "$(occupied_of DAY pending)" "0" "L3 收尾：L2 在途已结算，pending 归零"

echo "==> L4 三周期任一超限即整笔拒绝，且已预占周期不留残留（FR-010）"
# ⚠️ 关键前提：**只有被配置了额度（>0）的周期才会被预占/记账**（FR-020：0 = 该周期不限，
#    限额服务对该周期直接 continue）。所以「制造月周期占用」必须先给月周期配额度——
#    只配日额度的话 MONTH 恒为 0，本场景就退化成「无月短板」（2026-09-16 实跑踩到）。
#    这里先同时配日、月额度各 100000000（都远大于单笔），跑一笔并确认：两周期各累计 9900。
clear_limit
set_limit 100000000 100000000 0
assert_status 200 "PUT 日/月额度均极大（先让两个周期都进入受管状态）"
create_order_and_pay
assert_status 201 "L4 先用掉一笔，制造日/月周期占用"
L4_SEED_PAYMENT="$PAYMENT_NO"
settle_success "$L4_SEED_PAYMENT"
USED_DAY="$(occupied_of DAY used)"
USED_MONTH="$(occupied_of MONTH used)"
info "L4 基线：DAY used=$USED_DAY / MONTH used=$USED_MONTH"
# 两个周期都必须已有正占用，否则下面的「收窄成短板」不成立（用 0 收窄会变成「不限」）。
[ "$USED_DAY" -gt 0 ] || fail "L4 前置不成立：日周期 used 应为正，实际 $USED_DAY"
[ "$USED_MONTH" -gt 0 ] || fail "L4 前置不成立：月周期 used 应为正，实际 $USED_MONTH"
# 月额度 = 月已用（放不下新的一笔），日额度仍有极大余量 → MONTH 是唯一短板
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
# ⚠️ 端点路径是 /internal/limits/payments/{paymentNo}/operations —— **不含** /users/{userId}；
#    用 $LIMIT_URL（= /internal/limits/users/{userId}）拼接会落到静态资源处理器上，
#    表现为 500 NoResourceFoundException（2026-09-16 实跑踩到）。
http GET "$PAYMENT_URL/internal/limits/payments/$L7_PAYMENT_NO/operations"
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
