#!/usr/bin/env bash
# demo/lib.sh —— 演示脚本公共库：HTTP 封装、JSON 取值、断言（失败非零退出）
# 依赖：curl；JSON 解析优先 python3/python（jq 可选）。
set -uo pipefail

# ---- 颜色 ----
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# ---- 服务地址（可被 env 覆盖）----
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8081}"
CATALOG_URL="${CATALOG_URL:-http://localhost:8082}"
ORDER_URL="${ORDER_URL:-http://localhost:8083}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8084}"
REFUND_URL="${REFUND_URL:-http://localhost:8085}"
ENTITLEMENT_URL="${ENTITLEMENT_URL:-http://localhost:8087}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8090}"
RECON_URL="${RECON_URL:-http://localhost:8088}"
DEMO_URL="${DEMO_URL:-http://localhost:8091}"     # mock-channel-web
ADMIN_TOKEN="${PAYMENT_ADMIN_TOKEN:-demo-admin-token}"

# ---- HTTP 封装：打印请求 + 摘要，设置 STATUS/BODY ----
# 用法：http METHOD URL [BODY] [HEADERS]
#   HEADERS 用 '|' 分隔多个头，如 "Idempotency-Key: abc|Authorization: x"
http() { # http METHOD URL [BODY] [HEADERS]
  local method="$1" url="$2" body="${3:-}" headers="${4:-}"
  local -a curl_args=(-s -w '\n%{http_code}' -X "$method")
  if [ -n "$headers" ]; then
    IFS='|' read -ra _HDRS <<< "$headers"
    for _h in "${_HDRS[@]}"; do
      _h="$(echo "$_h" | sed 's/^ *//;s/ *$//')"
      [ -n "$_h" ] && curl_args+=(-H "$_h")
    done
  fi
  curl_args+=(-H 'Content-Type: application/json')
  if [ -n "$body" ]; then curl_args+=(-d "$body"); fi
  curl_args+=("$url")
  RESP="$(curl "${curl_args[@]}")"
  STATUS="$(echo "$RESP" | tail -n1)"
  BODY="$(echo "$RESP" | sed '$d')"
  echo "  > $method $url"
  [ -n "$body" ] && echo "    body: $body"
  [ -n "$headers" ] && echo "    headers: $headers"
  echo "    <- $STATUS $(echo "$BODY" | head -c 220)"
}

# ---- JSON 取值：json_get <expr>（expr 为 python 表达式，d 为 dict / list）----
json_get() {
  local expr="$1"
  echo "$BODY" | python -c "
import json,sys
d=json.load(sys.stdin)
try:
    r=$expr
except Exception:
    r=''
print('' if r is None else r)
" 2>/dev/null
}

VALUE=""   # json_get 的结果落在这里，便于 assert 使用
jget() { VALUE="$(json_get "$1")"; }

# ---- 断言 ----
assert_eq() { # assert_eq <actual> <expected> <label>
  if [ "$1" == "$2" ]; then info "PASS: $3 (== $2)"; else fail "$3: expected [$2] but got [$1]"; fi
}
assert_contains() {
  case "$1" in *"$2"*) info "PASS: $3 (contains '$2')";; *) fail "$3: expected to contain '$2' but got: $1";; esac
}
assert_status() { # assert_status <expected_http> <label>
  assert_eq "$STATUS" "$1" "$2"
}

# ---- 等待服务健康 ----
wait_service() { # wait_service <url> <name> [tries]
  local url="$1" name="$2" tries="${3:-60}"
  for i in $(seq 1 "$tries"); do
    if curl -s -o /dev/null -w '%{http_code}' "$url/actuator/health" 2>/dev/null | grep -q 200; then
      info "$name UP"
      return 0
    fi
    sleep 1
  done
  fail "$name 未在 ${tries}s 内就绪（$url/actuator/health）"
}

wait_for_services() {
  info "等待服务健康…"
  wait_service "$MERCHANT_URL" merchant
  wait_service "$CATALOG_URL"  catalog
  wait_service "$ORDER_URL"    order
  wait_service "$PAYMENT_URL"  payment
  wait_service "$REFUND_URL"   refund
  wait_service "$ENTITLEMENT_URL" entitlement
  wait_service "$LEDGER_URL"   ledger
  wait_service "$RECON_URL"    reconciliation
  wait_service "$DEMO_URL"     mock-channel-web
}

# ---- 轮询直到条件满足 ----
wait_until() { # wait_until <tries> <sleep_secs> <desc> <command...>   command 成功返回 0 即止
  local tries="$1" interval="$2" desc="$3"; shift 3
  for i in $(seq 1 "$tries"); do
    if "$@" >/dev/null 2>&1; then info "PASS: $desc（第 $i 次探测）"; return 0; fi
    sleep "$interval"
  done
  fail "$desc 在 $((tries * interval))s 内未达成"
}
