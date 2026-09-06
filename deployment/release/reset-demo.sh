#!/usr/bin/env bash
#
# reset-demo.sh —— 复位演示数据：重放建表 SQL（幂等）+ 清空事务表 + 灌确定性种子
# （商户 / 商品 / SKU 101 正价 · 102 退款 · 103 秒杀）
#
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$ROOT_DIR/deployment/demo/reset.sh"
