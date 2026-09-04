#!/usr/bin/env bash
# demo/seed.sh —— 仅灌种子数据（不重建 Schema），假定 deployment/start-all.sh 已起库与服务。
# 已 reset 过则直接重跑本脚本即可（API 创建为幂等覆盖 / 清空后重建）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

wait_for_services

echo "==> 灌种子数据（API，幂等）"
# --- 商户（内存仓储）：注册 + 审批 ---
http POST "$MERCHANT_URL/merchants" '{"code":"DEMO-M1","name":"demo-merchant-1","settlementAccountRef":"acct-demo-1"}'
assert_status 201 "商户注册"
jget "d['id']"; MERCHANT_ID="$VALUE"
http POST "$MERCHANT_URL/merchants/$MERCHANT_ID/approve"
assert_status 200 "商户审批（id=$MERCHANT_ID）"

# --- 商品：1 个已上架商品 ---
http POST "$CATALOG_URL/products" '{"productCode":"DEMO-P1","name":"demo-digital-member","type":"DIGITAL"}'
assert_status 201 "商品创建"
jget "d['id']"; PRODUCT_ID="$VALUE"
http POST "$CATALOG_URL/products/$PRODUCT_ID/list"
assert_status 200 "商品上架（id=$PRODUCT_ID）"

# --- SKU：101 正价（¥99.00）、102 退款用（¥129.00）---
create_sku() {
  http POST "$CATALOG_URL/skus" "{\"skuCode\":\"$1\",\"productId\":$PRODUCT_ID,\"name\":\"$2\",\"priceMinor\":$3,\"currencyCode\":\"CNY\",\"deliveryDefinition\":\"AUTO_GRANT\"}"
  assert_status 201 "SKU 创建 $1"
  jget "d['id']"; local id="$VALUE"
  http POST "$CATALOG_URL/skus/$id/activate"
  assert_status 200 "SKU 激活 $1（id=$id）"
  http POST "$CATALOG_URL/internal/stock/seed" "{\"skuId\":$id,\"total\":$4}"
  assert_status 200 "库存预置 $1（skuId=$id, total=$4）"
}
create_sku "DEMO-SKU-101" "monthly-membership" 9900 100
create_sku "DEMO-SKU-102" "annual-membership" 129000 100

echo ""
info "种子完成：商户=$MERCHANT_ID 商品=$PRODUCT_ID SKU=101/102"
