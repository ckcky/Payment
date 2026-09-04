#!/usr/bin/env bash
# =============================================================================
# traffic-gen.sh — Feature 015 / P6：后台全链路模拟交易流量（默认 2 TPS）
#
# 链路（Feature 015 语义）：
#   ① POST {order}/orders                        → orderNo（下单，不建支付单）
#   ② POST {order}/orders/{orderNo}/payments     → paymentNo（显式选渠道，可多支付单）
#   ③ 成败按概率分流（默认 92% 成功 / 3% 失败 / 5% UNKNOWN）：
#        SUCCESS → POST {demo}/mock-channel/callback（渠道代签回调 → 订单 PAID+扣库存）
#        FAILURE → 同上（status=FAILURE，仅支付单 FAILED，不动订单）
#        UNKNOWN → 同上（status=UNKNOWN），延迟 2s 后 POST /internal/payments/{no}/resolve 收敛
#   ④ 每第 N 笔（默认 10%）执行「换渠道再付」：同订单再建一张支付单并回调成功
#      （INV-2 演示：一交易多支付单，旧单保留 FAILED）
#
# 特性：
#   - 零 fork 取值（lib.sh 的 httpq/jstr/jnum），补偿式 sleep 控频
#   - 启动时自建大库存演示 SKU（避免库存耗尽），失败不阻断
#   - JSONL 逐笔汇总 + 终态统计
#
# 用法：
#   nohup bash deployment/demo/traffic-gen.sh &          # 后台运行
#   TPS=5 DURATION=60 FAIL_RATE=0.05 UNKNOWN_RATE=0.1 SWITCH_EVERY=5 bash .../traffic-gen.sh
#   bash deployment/demo/stop-traffic.sh                 # 停止
# =============================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

ORDER_URL="${ORDER_URL:-http://localhost:8083}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8084}"
DEMO_URL="${DEMO_URL:-http://localhost:8091}"     # mock-channel-web（回调代理，代签名）
TPS="${TPS:-2}"                                   # 每秒交易笔数
DURATION="${DURATION:-0}"                         # 运行秒数；0 = 不限，直到 stop-traffic.sh
FAIL_RATE="${FAIL_RATE:-3}"                       # FAILURE 概率（百分比整数）
UNKNOWN_RATE="${UNKNOWN_RATE:-5}"                 # UNKNOWN 概率（百分比整数）；其余为 SUCCESS
SWITCH_EVERY="${SWITCH_EVERY:-10}"                # 每 N 笔做一次「换渠道再付」（0=关闭）
UNKNOWN_RESOLVE_DELAY="${UNKNOWN_RESOLVE_DELAY:-2}" # UNKNOWN 后 resolve 延迟秒数

PID_FILE="$SCRIPT_DIR/traffic-gen.pid"
STATS_FILE="$SCRIPT_DIR/traffic-gen.stats"
JSONL_FILE="${TRAFFIC_JSONL:-$SCRIPT_DIR/traffic-gen.jsonl}"
LOG_FILE="${TRAFFIC_LOG:-$SCRIPT_DIR/traffic-gen.log}"

PID_FILE="$SCRIPT_DIR/traffic-gen.pid"

AUTH_HEADER=()
if [ -n "${PAYMENT_ADMIN_TOKEN:-}" ]; then
  AUTH_HEADER=(-H "X-Platform-Token: $PAYMENT_ADMIN_TOKEN")
fi

echo $$ > "$PID_FILE"
cleanup() { :; }  # 不删临时文件：safe-delete 钩子环境 rm 可达 10s+/次且 fail-closed；文件随 PID 覆盖写
trap cleanup EXIT INT TERM

TMP_ORD="${TMPDIR:-/tmp}/traffic.$$.ord"
TMP_PAY="${TMPDIR:-/tmp}/traffic.$$.pay"
TMP_CB="${TMPDIR:-/tmp}/traffic.$$.cb"

info "traffic-gen 启动：TPS=$TPS DURATION=${DURATION}s(0=不限) 成功/失败/未知=$((100 - FAIL_RATE - UNKNOWN_RATE))%/$FAIL_RATE%/$UNKNOWN_RATE% 换渠道=每${SWITCH_EVERY}笔"
info "order=$ORDER_URL payment=$PAYMENT_URL demo=$DEMO_URL"
info "JSONL：$JSONL_FILE   统计：$STATS_FILE"

# ---- 0) 自建大库存演示 SKU（skuCode=TRAFFIC-9901，真正播种；按 code 幂等，reset 后 id 会变） ----
CATALOG_URL="${CATALOG_URL:-http://localhost:8082}"
SEED_SKU="${TRAFFIC_SKU:-}"
if [ -z "$SEED_SKU" ]; then
  httpq GET "$CATALOG_URL/skus"
  SEED_SKU=$(python -c "
import json,sys
try:
    skus=json.load(open(sys.argv[1],encoding='utf-8'))
    m=[s['id'] for s in skus if str(s.get('skuCode','')).startswith('TRAFFIC-')]
    print(m[0] if m else '')
except Exception:
    pass" "$HTTPQ_FILE" 2>/dev/null)
  if [ -z "$SEED_SKU" ]; then
    httpq POST "$CATALOG_URL/products" '{"productCode":"TRAFFIC-P1","name":"traffic-demo","type":"DIGITAL"}'
    if [ "$HTTPQ_CODE" != "201" ] && [ "$HTTPQ_CODE" != "200" ]; then
      # productCode 冲突（历史残留且无 list 端点可反查 id）→ 用时间戳 code 保证拿到新商品
      httpq POST "$CATALOG_URL/products" "{\"productCode\":\"TRAFFIC-P1-$(date +%s)\",\"name\":\"traffic-demo\",\"type\":\"DIGITAL\"}"
    fi
    PRODUCT_ID=$(jnum "$HTTPQ_FILE" 'id')
    if [ -n "$PRODUCT_ID" ]; then
      httpq POST "$CATALOG_URL/skus" "{\"skuCode\":\"TRAFFIC-9901\",\"productId\":$PRODUCT_ID,\"name\":\"traffic-demo\",\"priceMinor\":100,\"currencyCode\":\"CNY\",\"deliveryDefinition\":\"AUTO_GRANT\"}"
      SEED_SKU=$(jnum "$HTTPQ_FILE" 'id')
      if [ -n "$SEED_SKU" ]; then
        httpq POST "$CATALOG_URL/skus/$SEED_SKU/activate"
        httpq POST "$CATALOG_URL/internal/stock/seed" "{\"skuId\":$SEED_SKU,\"total\":1000000}"
      fi
    fi
  fi
  [ -z "$SEED_SKU" ] && { warn "自建演示 SKU 失败——回退 SKU=1（库存有限，长跑会耗尽）"; SEED_SKU=1; }
fi
info "流量下单使用 SKU=$SEED_SKU"

CHANNELS=(ALIPAY WECHAT DOUYIN MOCK)
INTERVAL=$(awk "BEGIN{print 1/$TPS}")
ok=0; fail=0; unknown=0; switched=0; n=0
start_ts=$(date +%s)

rand100() { # 零 fork 伪随机：纳秒+计数器混合，够演示用（10# 强制十进制，防前导零被当八进制）
  local ns; ns=$(date +%N 2>/dev/null); ns=${ns:-0}
  echo $(( (10#$ns + n * 7919) % 100 ))
}

while :; do
  if [ "$DURATION" -gt 0 ] 2>/dev/null; then
    now=$(date +%s); [ $((now - start_ts)) -ge "$DURATION" ] && break
  fi

  n=$((n + 1))
  channel=${CHANNELS[$((n % ${#CHANNELS[@]}))]}
  ts=$(date '+%F %T')

  # ① 下单（不建支付单）
  httpq POST "$ORDER_URL/orders" "{\"userId\":\"traffic-u$n\",\"merchantId\":\"m1\",\"items\":[{\"skuId\":$SEED_SKU,\"quantity\":1}]}" "${AUTH_HEADER[@]}"
  if [ "$HTTPQ_CODE" != "201" ] && [ "$HTTPQ_CODE" != "200" ]; then
    fail=$((fail + 1)); echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"order\",\"result\":\"FAIL\",\"http\":\"$HTTPQ_CODE\"}" >> "$JSONL_FILE"
    sleep "$INTERVAL"; continue
  fi
  orderNo=$(jstr "$HTTPQ_FILE" orderNo)

  # ② 选渠道建支付单（Feature 015：每次选渠道新建一张支付单）
  httpq POST "$ORDER_URL/orders/$orderNo/payments" "{\"channelCode\":\"$channel\"}" "${AUTH_HEADER[@]}"
  paymentNo=$(jstr "$HTTPQ_FILE" paymentNo)
  if [ "$HTTPQ_CODE" != "201" ] || [ -z "$paymentNo" ]; then
    fail=$((fail + 1)); echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"create-payment\",\"orderNo\":\"$orderNo\",\"result\":\"FAIL\",\"http\":\"$HTTPQ_CODE\"}" >> "$JSONL_FILE"
    sleep "$INTERVAL"; continue
  fi

  # ③ 成败按概率分流回调；换渠道笔强制先 FAILURE（订单仍可付，供 ④ INV-2 演示）
  force_switch=0
  if [ "$SWITCH_EVERY" -gt 0 ] 2>/dev/null && [ $((n % SWITCH_EVERY)) -eq 0 ]; then
    force_switch=1
  fi
  status="SUCCESS"
  if [ "$force_switch" = "1" ]; then
    status="FAILURE"
  else
    r=$(rand100)
    if [ "$r" -lt "$UNKNOWN_RATE" ]; then status="UNKNOWN"
    elif [ "$r" -lt $((UNKNOWN_RATE + FAIL_RATE)) ]; then status="FAILURE"
    fi
  fi

  httpq POST "$DEMO_URL/mock-channel/callback" \
    "{\"paymentNo\":\"$paymentNo\",\"status\":\"$status\",\"channelReference\":\"traffic-$channel-$n-$RANDOM\",\"amountMinor\":null,\"signMode\":\"VALID\"}" \
    "${AUTH_HEADER[@]}"

  case "$status" in
    SUCCESS)
      ok=$((ok + 1))
      echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"callback\",\"orderNo\":\"$orderNo\",\"paymentNo\":\"$paymentNo\",\"channel\":\"$channel\",\"result\":\"SUCCESS\"}" >> "$JSONL_FILE" ;;
    FAILURE)
      fail=$((fail + 1))
      echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"callback\",\"orderNo\":\"$orderNo\",\"paymentNo\":\"$paymentNo\",\"channel\":\"$channel\",\"result\":\"FAILURE\"}" >> "$JSONL_FILE" ;;
    UNKNOWN)
      unknown=$((unknown + 1))
      echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"callback\",\"orderNo\":\"$orderNo\",\"paymentNo\":\"$paymentNo\",\"channel\":\"$channel\",\"result\":\"UNKNOWN\"}" >> "$JSONL_FILE"
      # 收敛需要管理员令牌（X-Admin-Token，缺 403）且 body 字段是 result（非 status）
      ( sleep "$UNKNOWN_RESOLVE_DELAY"
        curl -s --noproxy '*' -m 5 -o /dev/null -X POST \
          -H 'Content-Type: application/json' \
          -H "X-Admin-Token: ${PAYMENT_ADMIN_TOKEN:-demo-admin-token}" \
          -d "{\"result\":\"SUCCESS\",\"channelReference\":\"resolve-$paymentNo\"}" \
          "${PAYMENT_URL}/payments/$paymentNo/resolve" ) &
      ;;
  esac

  # ④ 换渠道再付（INV-2 演示）：仅当第一笔未完成订单（FAILURE）时换渠道，
  #    同订单新渠道再建一张支付单并回调成功；旧单保留 FAILED。
  #    （首笔 SUCCESS 后订单已 PAID，再建支付单会被 409 ORDER_NOT_PAYABLE 拒绝）
  if [ "$force_switch" = "1" ]; then
    sw=${CHANNELS[$(( (n + 1) % ${#CHANNELS[@]} ))]}
    httpq POST "$ORDER_URL/orders/$orderNo/payments" "{\"channelCode\":\"$sw\"}" "${AUTH_HEADER[@]}"
    swPay=$(jstr "$HTTPQ_FILE" paymentNo)
    if [ -n "$swPay" ]; then
      httpq POST "$DEMO_URL/mock-channel/callback" \
        "{\"paymentNo\":\"$swPay\",\"status\":\"SUCCESS\",\"channelReference\":\"traffic-$sw-$n-$RANDOM-swap\",\"amountMinor\":null,\"signMode\":\"VALID\"}" \
        "${AUTH_HEADER[@]}"
      switched=$((switched + 1))
      echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"switch-channel\",\"orderNo\":\"$orderNo\",\"oldPaymentNo\":\"$paymentNo\",\"newPaymentNo\":\"$swPay\",\"newChannel\":\"$sw\",\"result\":\"$([ "$HTTPQ_CODE" = "200" ] && echo SUCCESS || echo FAIL)\"}" >> "$JSONL_FILE"
    else
      echo "{\"n\":$n,\"ts\":\"$ts\",\"step\":\"switch-channel\",\"orderNo\":\"$orderNo\",\"result\":\"FAIL\",\"http\":\"$HTTPQ_CODE\"}" >> "$JSONL_FILE"
    fi
  fi

  echo "total=$n ok=$ok fail=$fail unknown=$unknown switched=$switched last_channel=$channel" > "$STATS_FILE"

  # 控频 sleep（补偿式留待后续精化：curl 耗时远小于 1/TPS=500ms 时误差可忽略）
  sleep "$INTERVAL"
done

echo "total=$n ok=$ok fail=$fail unknown=$unknown switched=$switched finished=$(date '+%F %T')" > "$STATS_FILE"
info "traffic-gen 结束：total=$n ok=$ok fail=$fail unknown=$unknown 换渠道=$switched"
