#!/usr/bin/env bash
# demo/reset.sh —— 演示环境复位：清空 8 个业务 Schema 的表 + 灌确定性种子数据
#
# 做法：重放 deployment/schema/*.sql（CREATE DATABASE/TABLE IF NOT EXISTS，全新与存量环境均可）
#       → TRUNCATE 各业务表（不断连接，服务无需重启）→ 通过 HTTP API 灌种子
#       （merchant 内存仓储 + catalog 商品/SKU）。
# 前提：docker compose 的 MySQL（容器名 payment-mysql）与服务已启动（bash deployment/start-all.sh）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

SCHEMA_DIR="$ROOT_DIR/deployment/schema"
DATABASES=(catalog "order" payment refund fulfillment entitlement reconciliation settlement ledger)

echo "==> [1/3] 重建业务 Schema（重放 deployment/schema/*.sql + 清空业务表）"
# 只重放「全量 schema」（NN-*.sql，各文件自带 CREATE DATABASE + USE）。
# 015-*.sql 是存量环境增量迁移脚本（前置 USE payment，靠 DATABASE() 定位库），
# 不属于全新初始化流程——reset 环境由 03-payment-schema.sql 直接建出最终表结构，
# 误放进来会因 mysql 客户端未选库报 ERROR 1046 No database selected。
for f in "$SCHEMA_DIR"/[0-9][0-9]-*.sql; do
  docker exec -i payment-mysql mysql -uroot -proot < "$f"
  echo "    applied $(basename "$f")"
done

# 收敛退款历史列：refund_items / refund_post_process_attempts 的 refund_id(BIGINT)
# → refund_no(VARCHAR)，ADR-0063 收口（2026-09）。reset 用 CREATE TABLE IF NOT EXISTS，
# 不会改造已存在表，故这里对存量表显式收敛一次；全新库已是 refund_no，检测到即跳过。
for t in refund_items refund_post_process_attempts; do
  old=$(docker exec -i payment-mysql mysql -uroot -proot -N -B -e \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='payment' AND table_name='$t' AND column_name='refund_id';" 2>/dev/null)
  [ "${old:-0}" = "1" ] || continue
  case "$t" in
    refund_items)
      docker exec -i payment-mysql mysql -uroot -proot -e \
        "USE \`payment\`; ALTER TABLE \`refund_items\` DROP INDEX idx_refund_items_refund_id, CHANGE COLUMN refund_id refund_no VARCHAR(32) NOT NULL, ADD INDEX idx_refund_items_refund_no (refund_no);" ;;
    refund_post_process_attempts)
      docker exec -i payment-mysql mysql -uroot -proot -e \
        "USE \`payment\`; ALTER TABLE \`refund_post_process_attempts\` DROP INDEX uk_rppa_refund_target, DROP INDEX idx_rppa_refund_id, CHANGE COLUMN refund_id refund_no VARCHAR(32) NOT NULL, ADD UNIQUE KEY uk_rppa_refund_target (refund_no, target), ADD INDEX idx_rppa_refund_no (refund_no);" ;;
  esac
  echo "    migrated $t: refund_id -> refund_no"
done

# 清空业务表：TRUNCATE 而非 DROP DATABASE。
# 原因（2026-09-05 实跑踩坑）：DROP DATABASE 会让运行中服务的 Hikari 连接池持有失效连接，
# MySQL 侧持续报 "Unknown database 'payment'"，且不会自愈——必须重启服务才恢复。
# TRUNCATE 只清数据、库与连接都保持有效，reset 完即可继续跑演示/压测。
TRUNC_DIR="${TMPDIR:-/tmp}/paymentarch-reset.$$"
mkdir -p "$TRUNC_DIR"
for db in "${DATABASES[@]}"; do
  SQL="SELECT CONCAT('TRUNCATE TABLE \`', table_name, '\`;') FROM information_schema.tables WHERE table_schema='$db';"
  if ! docker exec -i payment-mysql mysql -uroot -proot -N -B -e "$SQL" > "$TRUNC_DIR/$db.sql" 2>/dev/null; then
    fail "无法连接 MySQL 容器 payment-mysql（先跑 deployment/start-all.sh）"
  fi
  if [ -s "$TRUNC_DIR/$db.sql" ]; then
    { echo "SET FOREIGN_KEY_CHECKS=0;"; echo "USE \`$db\`;"; cat "$TRUNC_DIR/$db.sql"; } \
      | docker exec -i payment-mysql mysql -uroot -proot
    echo "    truncated $db"
  else
    echo "    truncated $db (无表，跳过)"
  fi
done

echo "==> [2/3] 等待服务健康"
wait_for_services

echo "==> [3/3] 灌种子数据（API，幂等：reset 后库已清空，直接创建）"
# --- 商户（内存仓储）：注册 + 审批 ---
http POST "$MERCHANT_URL/merchants" '{"code":"DEMO-M1","name":"demo-merchant-1","settlementAccountRef":"acct-demo-1"}'
# 商户注册可能返回：201 新建 / 409 code 已存在（内存仓储不随 DB reset 清空，此时取回确定性 id=1）
case "$STATUS" in
  200|201) info "PASS: 商户注册 (== $STATUS)"; jget "d['id']"; MERCHANT_ID="$VALUE" ;;
  409|500)
    if echo "$BODY" | grep -qi 'already exists\|already exist\|已存在'; then
      warn "商户已存在（内存仓储/历史进程残留），取回 id=1"; MERCHANT_ID=1
    else
      fail "商户注册: 非预期状态 [$STATUS] body=[$BODY]"
    fi
    ;;
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
http POST "$CATALOG_URL/products" '{"productCode":"DEMO-P1","name":"demo-digital-member","type":"DIGITAL"}'
assert_status 201 "商品创建"
jget "d['id']"; PRODUCT_ID="$VALUE"
http POST "$CATALOG_URL/products/$PRODUCT_ID/list"
assert_status 200 "商品上架（id=$PRODUCT_ID）"

# --- SKU：101 正价（99.00 CNY）、102 退款用（129.00 CNY）、103 秒杀（1.00 CNY，Phase 4 启用） ---
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
# --- 清理 Redis 陈旧秒杀配额键（必须在播种前）---
# 秒杀准入按「键存在=秒杀品」判断：历史残留的 seckill:sku:1=0 会让普通 SKU 被误判
# 秒杀配额耗尽（409 seckill stock insufficient）。复位时先清空再重播种 103。
docker exec payment-redis redis-cli EVAL "for _,k in ipairs(redis.call('keys','seckill:sku:*')) do redis.call('del',k) end" 0 >/dev/null 2>&1 || true

create_sku "DEMO-SKU-101" "monthly-membership"   9900     100
create_sku "DEMO-SKU-102" "annual-membership" 129000     100
create_sku "DEMO-SKU-103" "flash-sale-membership"    100      10   10

info "Redis 陈旧秒杀键已清理；配额仅 sku=3（=10）"

echo ""
info "复位完成：商户=$MERCHANT_ID 商品=$PRODUCT_ID SKU=101/102/103（103 已播种秒杀配额）"
