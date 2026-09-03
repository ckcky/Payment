#!/usr/bin/env bash
# demo/reset.sh —— 演示环境复位：重建 8 个业务 Schema 的表 + 灌确定性种子数据
#
# 做法：DROP 8 个业务库（不碰 mysql 系统库）→ 重放 deployment/schema/*.sql（建库建表）
#       → 通过 HTTP API 灌种子（merchant 内存仓储 + catalog 商品/SKU）。
# 前提：docker compose 的 MySQL（容器名 payment-mysql）与服务已启动（bash deployment/start-all.sh）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

SCHEMA_DIR="$ROOT_DIR/deployment/schema"
DATABASES=(catalog "order" payment refund fulfillment entitlement reconciliation settlement ledger)

echo "==> [1/3] 重建业务 Schema（DROP + 重放 deployment/schema/*.sql）"
for db in "${DATABASES[@]}"; do
  docker exec -i payment-mysql mysql -uroot -proot \
    -e "DROP DATABASE IF EXISTS \`$db\`;" 2>/dev/null \
    && echo "    dropped $db" || fail "无法连接 MySQL 容器 payment-mysql（先跑 deployment/start-all.sh）"
done
for f in "$SCHEMA_DIR"/*.sql; do
  docker exec -i payment-mysql mysql -uroot -proot < "$f"
  echo "    applied $(basename "$f")"
done

echo "==> [2/3] 等待服务健康"
wait_for_services

echo "==> [3/3] 灌种子数据（API，幂等：reset 后库已清空，直接创建）"
# --- 商户（内存仓储）：注册 + 审批 ---
http POST "$MERCHANT_URL/merchants" '{"code":"DEMO-M1","name":"演示商户一号","settlementAccountRef":"acct-demo-1"}'
# 商户注册可能返回：201 新建 / 409 code 已存在（内存仓储不随 DB reset 清空，此时取回确定性 id=1）
case "$STATUS" in
  200|201) info "PASS: 商户注册 (== $STATUS)"; jget "d['id']"; MERCHANT_ID="$VALUE" ;;
  409) warn "商户已存在（内存仓储），取回 id=1"; MERCHANT_ID=1 ;;
  *)   fail "商户注册: 非预期状态 [$STATUS]" ;;
esac
# 审批：200 正常 / 409 已审批过（内存仓储跨 reset 保留），二者皆视为就绪
http POST "$MERCHANT_URL/merchants/$MERCHANT_ID/approve"
case "$STATUS" in
  200) info "PASS: 商户审批（id=$MERCHANT_ID）" ;;
  409) warn "商户已审批过，跳过（id=$MERCHANT_ID）" ;;
  *)   fail "商户审批（id=$MERCHANT_ID）: 非预期状态 [$STATUS]" ;;
esac

# --- 商品：1 个已上架商品 ---
http POST "$CATALOG_URL/products" '{"productCode":"DEMO-P1","name":"演示商品·数字会员","type":"DIGITAL"}'
assert_status 201 "商品创建"
jget "d['id']"; PRODUCT_ID="$VALUE"
http POST "$CATALOG_URL/products/$PRODUCT_ID/list"
assert_status 200 "商品上架（id=$PRODUCT_ID）"

# --- SKU：101 正价（¥99.00）、102 退款用（¥129.00）、103 秒杀（¥1.00，Phase 4 启用） ---
# create_sku <code> <name> <priceMinor> <stockTotal> [<seckillTotal>]
create_sku() {
  http POST "$CATALOG_URL/skus" "{\"skuCode\":\"$1\",\"productId\":$PRODUCT_ID,\"name\":\"$2\",\"priceMinor\":$3,\"currencyCode\":\"CNY\",\"deliveryDefinition\":\"AUTO_GRANT\"}"
  assert_status 201 "SKU 创建 $1"
  jget "d['id']"; local id="$VALUE"
  http POST "$CATALOG_URL/skus/$id/activate"
  assert_status 200 "SKU 激活 $1（id=$id）"
  # 预置库存（三段式：下单预占 → 支付成功确认扣减）
  http POST "$CATALOG_URL/internal/stock/seed" "{\"skuId\":$id,\"total\":$4}"
  assert_status 200 "库存预置 $1（skuId=$id, total=$4）"
  # 可选：秒杀配额预扣（Phase 4，Redis Lua 原子准入）。端点为 @RequestParam 形态，参数走查询串
  if [ -n "${5:-}" ]; then
    http POST "$CATALOG_URL/internal/stock/seckill/seed?skuId=$id&total=$5"
    assert_status 200 "秒杀配额预置 $1（skuId=$id, total=$5）"
  fi
}
create_sku "DEMO-SKU-101" "月度会员卡"   9900     100
create_sku "DEMO-SKU-102" "年度会员卡" 129000     100
create_sku "DEMO-SKU-103" "秒杀体验卡"    100      10   10

echo ""
info "复位完成：商户=$MERCHANT_ID 商品=$PRODUCT_ID SKU=101/102/103（103 已播种秒杀配额）"
