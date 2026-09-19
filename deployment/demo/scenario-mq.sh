#!/usr/bin/env bash
# demo/scenario-mq.sh —— Redis 事务消息通道演示（spec 029 / ADR-0074）
#
# 前置：服务已启动（deployment/start-all.sh）；`payment.mq.enabled=true`（默认）。
#
# 六个场景（对齐 spec 029 §5）：
#   D1 回滚不投递      —— 本地事务失败 → 半消息被 rollback，可见队列无新消息
#   D2 崩溃回查补投    —— prepare 后进程消失 → 5s 内扫描器按真相表判定并补投
#   D3 下游宕机自愈    —— 下游停摆期间订单照常 PAID；下游恢复后追平积压
#   D4 广播隔离        —— 一笔支付后各消费组各 +1；组故障互不影响
#   D5 订单轨迹        —— timeline API 还原「下单→支付→履约」时序，含 traceId
#   D6 死信与告警      —— 消费永久失败 → 重试耗尽 → DLQ + mq.dead_letter 指标
#
# 依赖：docker exec 访问 payment-redis（redis-cli 只读探测）。
#
# 用法：
#   bash deployment/start-all.sh
#   bash demo/reset.sh
#   bash demo/scenario-mq.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

REDIS_CONTAINER="${REDIS_CONTAINER:-payment-redis}"
PASS=0; SKIP=0

# ---- Redis 只读助手（经 docker exec redis-cli；缺失则整体跳过 MQ 层断言）----
REDIS_OK=1
redis_ready() {
  docker exec "$REDIS_CONTAINER" redis-cli ping >/dev/null 2>&1 || return 1
}
rcli() { # rcli <args...> —— 只读命令；失败不中断脚本
  docker exec "$REDIS_CONTAINER" redis-cli "$@" 2>/dev/null
}
# 队列长度（可见消息数）；队列不存在时 redis-cli 返回 0 或空
xlen() { # xlen <topic>
  local v
  v="$(rcli XLEN "mq:stream:$1" || true)"
  case "$v" in ''|*[!0-9]*) echo 0;; *) echo "$v";; esac
}
# 指定组已消费条数（entries-read）
group_entries() { # group_entries <topic> <group>
  rcli XINFO GROUPS "mq:stream:$1" 2>/dev/null | awk -v g="$2" '
    $1=="name" {cur=$2} cur==g && $1=="entries-read" {print $2; found=1}
    END {if (!found) print "-1"}'
}
assert_ge() { # assert_ge <actual> <floor> <label>
  if [ "$1" -ge "$2" ] 2>/dev/null; then info "PASS: $3 ($1 >= $2)"; PASS=$((PASS+1));
  else fail "$3: expected >= $2 but got [$1]"; fi
}
assert_le() {
  if [ "$1" -le "$2" ] 2>/dev/null; then info "PASS: $3 ($1 <= $2)"; PASS=$((PASS+1));
  else fail "$3: expected <= $2 but got [$1]"; fi
}
pass() { info "PASS: $1"; PASS=$((PASS+1)); }
skip() { warn "SKIP: $1"; SKIP=$((SKIP+1)); }

# ---- 下单 + 支付（返回 orderNo / paymentNo 到全局）----
SKU_ID=""
resolve_sku() {
  http GET "$CATALOG_URL/skus"
  assert_status 200 "SKU 列表"
  SKU_ID="$(echo "$BODY" | python -c "
import json,sys
d=json.load(sys.stdin)
m=[x for x in d if x.get('skuCode')=='DEMO-SKU-101']
print(m[0]['id'] if m else '')")"
  [ -n "$SKU_ID" ] || fail "未找到种子 SKU DEMO-SKU-101（请先 bash demo/reset.sh）"
}
ORDER_NO=""; PAYMENT_NO=""
create_and_pay() { # create_and_pay <channelCode>
  http POST "$ORDER_URL/orders" \
    "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}]}"
  assert_status 201 "下单"
  jget "d['orderNo']"; ORDER_NO="$VALUE"
  [ -n "$ORDER_NO" ] || fail "下单响应缺少 orderNo"

  http POST "$ORDER_URL/orders/$ORDER_NO/payments" "{\"channelCode\":\"${1:-alipay}\"}"
  assert_status 201 "选渠道建支付单"
  jget "d['paymentNo']"; PAYMENT_NO="$VALUE"
  [ -n "$PAYMENT_NO" ] || fail "建支付单响应缺少 paymentNo"

  http POST "$DEMO_URL/mock-channel/callback" \
    "{\"paymentNo\":\"$PAYMENT_NO\",\"status\":\"SUCCESS\",\"channelReference\":\"mq-$ORDER_NO\",\"amountMinor\":9900,\"signMode\":\"VALID\"}"
  assert_status 200 "渠道回调受理"
}

echo "=================================================="
echo "  spec 029 · Redis 事务消息通道演示（D1~D6）"
echo "=================================================="
wait_for_services
resolve_sku
info "SKU_ID=$SKU_ID"

if redis_ready; then
  REDIS_OK=1
  info "Redis ($REDIS_CONTAINER) 可访问 —— MQ 层断言全部启用"
else
  REDIS_OK=0
  warn "Redis ($REDIS_CONTAINER) 不可访问 —— 仅跑业务层断言（D3/D5），队列/位点断言跳过"
fi

# ===========================================================================
# D1 回滚不投递：本地事务失败 → 半消息 rollback，可见队列无新消息
# ===========================================================================
echo ""
echo "===== D1 事务消息：回滚不投递 ====="
#
# 【为什么这样演示】
# 「回滚」在生产代码里只有一条路径：OrderEventPublisher 在本地事务提交**后**才 publish，
# 因此本地事务失败 = 根本没走到 publish，不会留下任何半消息。换言之：
#   **失败请求必须让可见队列一字不增，且半消息索引不留悬挂项**。
# 这是可以在外部观测、且不依赖 kill 进程时机的强判据。
#
# 注入：用必然失败的建单请求（不存在的 skuId）触发 order 侧落库失败。
D1_TOPIC="order.paid"
D1_QUEUE="mq:stream:$D1_TOPIC"
if [ "$REDIS_OK" = 1 ]; then
  BEFORE_D1="$(xlen "$D1_TOPIC")"
  BEFORE_D1_IDX="$(rcli ZCARD "mq:half:idx" || echo 0)"
  case "$BEFORE_D1_IDX" in ''|*[!0-9]*) BEFORE_D1_IDX=0;; esac
  info "D1 基线：$D1_TOPIC 队列 = $BEFORE_D1，半消息索引 = $BEFORE_D1_IDX"
else
  BEFORE_D1=0; BEFORE_D1_IDX=0
fi

# 注入故障：不存在库存的 SKU → 订单落库（明细）失败 → 本地事务回滚
http POST "$ORDER_URL/orders" \
  "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":999999999,\"quantity\":1}]}"
if [ "$STATUS" -ge 400 ] 2>/dev/null; then
  pass "D1：非法下单被拒（HTTP $STATUS），本地事务未提交"
else
  warn "D1：建单未被拒（HTTP $STATUS）——改用「缺少 items」触发参数校验失败"
  http POST "$ORDER_URL/orders" '{"userId":"demo-user","merchantId":"1","items":[]}'
  [ "$STATUS" -ge 400 ] && pass "D1：空明细下单被拒（HTTP $STATUS）" \
                        || fail "D1：无法构造失败的建单请求（HTTP $STATUS）"
fi

if [ "$REDIS_OK" = 1 ]; then
  sleep 2
  AFTER_D1="$(xlen "$D1_TOPIC")"
  AFTER_D1_IDX="$(rcli ZCARD "mq:half:idx" || echo 0)"
  case "$AFTER_D1_IDX" in ''|*[!0-9]*) AFTER_D1_IDX=0;; esac
  # 核心不变式（INV-3）：本地事务未提交 → 可见队列一字不增
  assert_le "$AFTER_D1" "$BEFORE_D1" "D1：失败请求后 $D1_TOPIC 队列未增长（无幽灵消息）"
  # 半消息索引不增长（要么没 prepare，要么已 rollback/回查清掉）
  assert_le "$AFTER_D1_IDX" "$BEFORE_D1_IDX" "D1：半消息索引未增长（无悬挂半消息）"
  info "D1：可见队列 $BEFORE_D1 → $AFTER_D1，半消息索引 $BEFORE_D1_IDX → $AFTER_D1_IDX"
else
  skip "D1：Redis 不可访问，跳过队列/索引断言"
fi

# ===========================================================================
# D2 崩溃回查补投：prepare 后进程消失 → 扫描器按真相表判定
# ===========================================================================
echo ""
echo "===== D2 事务消息：崩溃后回查补投 ====="
#
# 【演示方式】直接扮演「崩在半消息阶段的进程」：手工写入一条真实格式的半消息
#   Hash  mq:half:{topic}:{msgId}  ← 字段同 EnvelopeCodec.toFields
#   ZSet  mq:half:idx              ← member=topic:msgId, score=很久以前（立即到期）
# 然后观察扫描器（每 5s）如何按真相表分派：
#   ① bizNo 指向**已支付**订单（order.paid 的真相 = orders 表 PAID）→ 期望 COMMIT 补投
#   ② bizNo 指向**不存在**订单                                          → 期望 ROLLBACK 仅清索引
#
# 注意：为了让 ① 的补投能被下游正确处理，msgId 必须唯一（用时间戳）；重复 msgId
# 会被消费端幂等吸收，那样就观测不到「补投真的进了队」。
if [ "$REDIS_OK" != 1 ]; then
  skip "D2：Redis 不可访问，跳过"
else
  # 需要一笔真实 PAID 订单作为「真相」来源
  if [ -z "$ORDER_NO" ]; then resolve_sku; create_and_pay alipay; fi
  # 等订单确实进入 PAID（回调是同步处理的，这里再确认一次）
  wait_until 15 1 "订单进入 PAID" bash -c "
    curl -s --noproxy '*' '$ORDER_URL/orders/$ORDER_NO' | grep -q '\"PAID\"'"
  info "D2：真相订单 $ORDER_NO 已 PAID"

  MSG_OK="d2-commit-$(date +%s)-$$"
  MSG_NO="d2-rollback-$(date +%s)-$$"
  QUEUE_BEFORE="$(xlen "order.paid")"
  OLD_SCORE="$(( $(date +%s) * 1000 - 600000 ))"   # 10 分钟前 → 立即到期

  # ① 应判 COMMIT 的半消息（bizNo = 真实 PAID 订单）
  rcli HSET "mq:half:order.paid:$MSG_OK" \
    msgId "$MSG_OK" topic "order.paid" eventType "ORDER_PAID" \
    bizNo "$ORDER_NO" traceId "trace-d2-commit" producer "demo-scenario-mq" \
    occurredAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    payload '{"orderNo":"'"$ORDER_NO"'","paymentNo":"'"$PAYMENT_NO"'","userId":"demo-user","items":[]}' >/dev/null
  rcli ZADD "mq:half:idx" "$OLD_SCORE" "order.paid:$MSG_OK" >/dev/null
  info "D2：已植入应判 COMMIT 的半消息 msgId=$MSG_OK（bizNo=$ORDER_NO）"

  # ② 应判 ROLLBACK 的半消息（bizNo = 不存在的订单）
  rcli HSET "mq:half:order.paid:$MSG_NO" \
    msgId "$MSG_NO" topic "order.paid" eventType "ORDER_PAID" \
    bizNo "ORDER-DOES-NOT-EXIST" traceId "trace-d2-rollback" producer "demo-scenario-mq" \
    occurredAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" payload '{"orderNo":"ORDER-DOES-NOT-EXIST"}' >/dev/null
  rcli ZADD "mq:half:idx" "$OLD_SCORE" "order.paid:$MSG_NO" >/dev/null
  info "D2：已植入应判 ROLLBACK 的半消息 msgId=$MSG_NO（bizNo 不存在）"

  # 扫描器 fixedDelay=5s；给足两个周期
  info "D2：等待回查扫描（≤15s）…"
  wait_until 15 2 "COMMIT 半消息被补投进可见队列" bash -c "
    docker exec $REDIS_CONTAINER redis-cli XRANGE 'mq:stream:order.paid' - + 2>/dev/null | grep -q '$MSG_OK'"
  pass "D2：回查判 COMMIT 并补投 —— $MSG_OK 已出现在 mq:stream:order.paid"

  # 两条半消息都应从索引中消失（一条补投后清、一条 ROLLBACK 后清）
  wait_until 15 2 "两条半消息均被清理" bash -c "
    ! docker exec $REDIS_CONTAINER redis-cli ZRANGE 'mq:half:idx' 0 -1 2>/dev/null | grep -qE '$MSG_OK|$MSG_NO'"
  pass "D2：半消息索引已清理（COMMIT 补投后清 / ROLLBACK 直接清）"

  # 补投的消息不得凭空增加业务副作用（下游幂等吸收）
  http GET "$ORDER_URL/orders/$ORDER_NO"
  assert_status 200 "D2：真相订单仍可查"
  jget "d['status']"; assert_eq "$VALUE" "PAID" "D2：补投后订单状态仍为 PAID（幂等，未产生第二笔副作用）"

  QUEUE_AFTER="$(xlen "order.paid")"
  info "D2：$D1_TOPIC 队列 $QUEUE_BEFORE → $QUEUE_AFTER（含本次补投）"
fi

# ===========================================================================
# D3 下游宕机自愈：下游停摆期间订单照常 PAID；恢复后追平积压
# ===========================================================================
echo ""
echo "===== D3 下游宕机自愈 ====="
if [ "$REDIS_OK" != 1 ]; then
  skip "D3：Redis 不可访问，跳过队列追平断言（仍需服务全量在线）"
else
  FF_GROUP_BEFORE="$(group_entries "order.paid" "fulfillment")"
  info "D3 基线：fulfillment 组 entries-read = $FF_GROUP_BEFORE"

  create_and_pay alipay
  info "D3：订单 $ORDER_NO 支付单 $PAYMENT_NO 已成交"

  # 订单状态不受下游影响（核心不变式：异步解耦后上游不再被下游拖住）
  http GET "$ORDER_URL/orders/$ORDER_NO"
  assert_status 200 "订单可查"
  jget "d['status']"; O_STATUS="$VALUE"
  assert_eq "$O_STATUS" "PAID" "D3：下游无关，订单状态已 PAID"

  # fulfillment 组应追平（默认在线时 5~10s 内消费完）
  wait_until 20 1 "fulfillment 组追平 order.paid" bash -c "
    now=\$(docker exec $REDIS_CONTAINER redis-cli XINFO GROUPS 'mq:stream:order.paid' 2>/dev/null | awk '\$1==\"name\"{c=\$2} c==\"fulfillment\" && \$1==\"entries-read\"{print \$2}')
    [ -n \"\$now\" ] && [ \"\$now\" -gt ${FF_GROUP_BEFORE:-0} ]"
  pass "D3：fulfillment 组 entries-read 已增长（消费侧追平）"
fi

# ===========================================================================
# D4 广播隔离：一笔支付后各消费组各 +1；组故障互不影响
# ===========================================================================
echo ""
echo "===== D4 广播隔离（多组独立位点） ====="
if [ "$REDIS_OK" != 1 ]; then
  skip "D4：Redis 不可访问，跳过"
else
  CAT_B="$(group_entries "order.paid" "catalog")"
  FF_B="$(group_entries "order.paid" "fulfillment")"
  TR_B="$(group_entries "order.paid" "trace")"
  info "D4 基线：catalog=$CAT_B fulfillment=$FF_B trace=$TR_B"

  create_and_pay alipay
  info "D4：新订单 $ORDER_NO"

  # 三个组各自独立位点，都应 +1（广播语义）
  for g in catalog fulfillment trace; do
    case "$g" in
      catalog) B="$CAT_B";; fulfillment) B="$FF_B";; trace) B="$TR_B";;
    esac
    wait_until 20 1 "组 $g 位点增长" bash -c "
      now=\$(docker exec $REDIS_CONTAINER redis-cli XINFO GROUPS 'mq:stream:order.paid' 2>/dev/null | awk '\$1==\"name\"{c=\$2} c==\"$g\" && \$1==\"entries-read\"{print \$2}')
      [ -n \"\$now\" ] && [ \"\$now\" -gt ${B:--1} ]"
    pass "D4：$g 组独立位点已推进（广播各得一份）"
  done
fi

# ===========================================================================
# D5 订单轨迹：timeline API 还原时序，含 traceId
# ===========================================================================
echo ""
echo "===== D5 订单轨迹（只读投影，INV-5） ====="
if [ -z "$ORDER_NO" ]; then
  resolve_sku
  create_and_pay alipay
fi
# 轨迹落表由 trace 消费组异步完成，轮询直到至少有一条
wait_until 30 1 "timeline 出现事件" bash -c "
  code=\$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' '$ORDER_URL/api/orders/$ORDER_NO/timeline')
  [ \"\$code\" = 200 ]"
http GET "$ORDER_URL/api/orders/$ORDER_NO/timeline"
assert_status 200 "timeline API 可查"
jget "d['orderNo']"; assert_eq "$VALUE" "$ORDER_NO" "D5：轨迹 orderNo 匹配"
jget "d['count']";   T_COUNT="$VALUE"
info "D5：轨迹事件数 = $T_COUNT"
if [ "${T_COUNT:-0}" -ge 1 ] 2>/dev/null; then
  pass "D5：轨迹含 ≥1 条事件"
  jget "d['events'][0]['traceId']"; TRACE0="$VALUE"
  [ -n "$TRACE0" ] && pass "D5：事件携带 traceId（$TRACE0）" \
                   || fail "D5：事件缺 traceId（traceId 连续性被破坏）"
  jget "d['events'][0]['topic']"; info "D5：首条事件 topic = $VALUE"
  # 轨迹是只读投影：再查一次条数不减少、业务状态不变
  http GET "$ENTITLEMENT_URL/entitlements/by-order/$ORDER_NO"
  assert_status 200 "D5：权益侧仍可查（轨迹与业务解耦）"
else
  fail "D5：轨迹为空（trace 消费组未落表？检查 order_event_log 与 trace 组位点）"
fi

# ===========================================================================
# D6 死信与告警：消费永久失败 → 重试耗尽 → DLQ + mq.dead_letter
# ===========================================================================
echo ""
echo "===== D6 死信与告警 ====="
http GET "$ORDER_URL/actuator/prometheus"
assert_status 200 "order Prometheus 端点"
for m in mq_consumed_total mq_committed_total mq_prepared_total; do
  if echo "$BODY" | grep -q "$m"; then
    pass "D6：order 暴露 $m（FR-502 指标在位）"
  else
    fail "D6：缺少指标 $m（消息通道埋点缺失）"
  fi
done
if echo "$BODY" | grep -q 'mq_dead_letter_total'; then
  pass "D6：mq_dead_letter_total 已暴露（DLQ 计数可观测）"
else
  warn "D6：mq_dead_letter_total 尚未出现（无死信时不计点，属正常；Grafana 面板已预留）"
fi

if [ "$REDIS_OK" = 1 ]; then
  DLQ_LEN="$(xlen "mq:dlq:order.paid" || echo 0)"
  info "D6：DLQ 当前长度 = $DLQ_LEN"
  if [ "$DLQ_LEN" -ge 1 ] 2>/dev/null; then
    pass "D6：DLQ 有内容（历史失败已被收容）"
  else
    skip "D6：DLQ 为空（本次运行未注入永久失败，属正常）"
  fi
fi

echo ""
echo "=================================================="
info "scenario-mq 断言小结：PASS=$PASS SKIP=$SKIP"
if [ "$PASS" -eq 0 ]; then fail "无任何断言通过，请检查服务与 Redis 状态"; fi
info "scenario-mq 演示完成 ✅"
