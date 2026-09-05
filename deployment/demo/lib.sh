#!/usr/bin/env bash
# demo/lib.sh —— 演示脚本公共库：HTTP 封装、JSON 取值、断言（失败非零退出）
# 依赖：curl；JSON 解析优先 python3/python（jq 可选）。
set -uo pipefail

# 兼容 Git Bash 沙箱：外层若设置 MSYS_NO_PATHCONV=1 / MSYS2_ARG_CONV_EXCL=*，
# Windows 原生 curl 会把 -o /dev/null 当字面路径写失败（exit 23），健康检查永远 FAIL。
# 恢复路径转换即可（本库的 curl 参数均为 URL/头部，不含需要保护的正斜杠路径）。
unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

# 兼容注入 HTTP_PROXY 的环境（沙箱代理 / 企业内网代理）：回环地址被代理会挂起或返回 502，
# 使 wait_service 永远等不到健康。这里显式放行 localhost，并在所有 curl 调用加 --noproxy。
export NO_PROXY="localhost,127.0.0.1,::1${NO_PROXY:+,$NO_PROXY}"
export no_proxy="$NO_PROXY"

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
# Feature 015/P3: 退款 API 随 refund 并入 payment-service，端口 8085→8084
REFUND_URL="${REFUND_URL:-http://localhost:8084}"
ENTITLEMENT_URL="${ENTITLEMENT_URL:-http://localhost:8087}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8090}"
RECON_URL="${RECON_URL:-http://localhost:8088}"
SETTLEMENT_URL="${SETTLEMENT_URL:-http://localhost:8089}"
DEMO_URL="${DEMO_URL:-http://localhost:8091}"     # mock-channel-web
ADMIN_TOKEN="${PAYMENT_ADMIN_TOKEN:-demo-admin-token}"

# ---- HTTP 封装：打印请求 + 摘要，设置 STATUS/BODY ----
# 用法：http METHOD URL [BODY] [HEADERS]
#   HEADERS 用 '|' 分隔多个头，如 "Idempotency-Key: abc|Authorization: x"
http() { # http METHOD URL [BODY] [HEADERS]
  local method="$1" url="$2" body="${3:-}" headers="${4:-}"
  local -a curl_args=(-s --noproxy '*' -w '\n%{http_code}' -X "$method")
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
    if curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$url/actuator/health" 2>/dev/null | grep -q 200; then
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

# =============================================================================
# Feature 015 / P6：高频流量零 fork 助手
# 2 TPS 起步的流量脚本里，每次 $(...) 子 shell / python 起进程都是开销，
# 这里提供「临时文件 + grep/sed」路径的零 fork 版本。
# =============================================================================

# httpq <METHOD> <URL> [BODY] [HEADER...] —— curl 直出临时文件，不经命令替换
# 结果：HTTPQ_CODE（http 状态码）/ HTTPQ_FILE（响应体临时文件，调用方负责清理或复用）
HTTPQ_FILE=""
HTTPQ_CODE=""
httpq() {
  local method="$1" url="$2" body="${3:-}"; shift 3
  HTTPQ_FILE="${TMPDIR:-/tmp}/httpq.$$.response"
  local code_file="${TMPDIR:-/tmp}/httpq.$$.code"
  local args=(-s --noproxy '*' -m 5 -o "$HTTPQ_FILE" -w '%{http_code}')
  local h
  for h in "$@"; do args+=(-H "$h"); done
  if [ -n "$body" ]; then
    args+=(-X "$method" -H 'Content-Type: application/json' -d "$body")
  else
    [ "$method" != "GET" ] && args+=(-X "$method")
  fi
  curl "${args[@]}" "$url" > "$code_file" 2>/dev/null
  HTTPQ_CODE=$(cat "$code_file" 2>/dev/null)
  # 不删 code_file：同 PID 覆盖写、体积极小；safe-delete 钩子环境下 rm 单次可达 10s+，
  # 会把压测/演示吞吐拖垮（2026-09-05 实测踩坑）。
}

# jstr <FILE> <field> —— 从 JSON 文件零 fork 提取字符串字段值（stdout 输出）
jstr() { # jstr <file> <field>
  grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$1" 2>/dev/null \
    | head -1 | sed "s/\"$2\"[[:space:]]*:[[:space:]]*\"//;s/\"[[:space:]]*$//"
}

# jnum <FILE> <field> —— 从 JSON 文件零 fork 提取数值字段值（stdout 输出）
jnum() { # jnum <file> <field>
  grep -o "\"$2\"[[:space:]]*:[[:space:]]*[-0-9.]*" "$1" 2>/dev/null \
    | head -1 | sed "s/\"$2\"[[:space:]]*:[[:space:]]*//"
}
