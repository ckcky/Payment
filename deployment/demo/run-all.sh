#!/usr/bin/env bash
# demo/run-all.sh —— 一键跑通全部演示场景（复位 → 主链 → 退款 → UNKNOWN 收敛 → 每日对账）
#
# 前提：
#   1) docker compose 起的 MySQL + Prometheus/Grafana 已就绪；
#   2) bash deployment/start-all.sh 已启动全量服务（含 mock-channel-web，且 mock-cashier 已开启）；
#   3) PAYMENT_ADMIN_TOKEN 已设置（start-all.sh 默认已 export 演示令牌，供 UNKNOWN 收敛端点鉴权）。
#
# 注意：渠道回调验签当前为 ADR-0025 占位（恒放行），「伪造签名被拒」演示不可达，详见 demo/README.md。
#
# 用法：
#   bash deployment/start-all.sh          # 先起服务
#   bash demo/run-all.sh                 # 再跑演示
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

# 演示环境兜底：restart-payment.sh 会重启 payment-service，若本 shell 未导出这些变量，
# 重启后的 payment 将丢失 cashier 路径/管理令牌（与 start-all.sh 的默认值保持一致）。
export PAYMENT_MOCK_CASHIER_ENABLED="${PAYMENT_MOCK_CASHIER_ENABLED:-true}"
export PAYMENT_ADMIN_TOKEN="${PAYMENT_ADMIN_TOKEN:-demo-admin-token}"
export PAYMENT_CHANNEL_SECRET="${PAYMENT_CHANNEL_SECRET:-demo-channel-secret-2026}"

echo "=================================================="
echo "  PaymentArch 演示总入口"
echo "=================================================="
bash "$HERE/reset.sh"
bash "$HERE/scenario-happy-path.sh"
bash "$HERE/scenario-refund.sh"
# UNKNOWN 路径需 payment-service 以 BUSINESS_UNKNOWN 场景运行（构造期注入，ADR-0049）
bash "$HERE/restart-payment.sh" BUSINESS_UNKNOWN
bash "$HERE/scenario-payment-unknown.sh"
bash "$HERE/restart-payment.sh" SUCCESS
bash "$HERE/scenario-reconciliation.sh"

echo ""
info "✅ 全部演示场景通过（主链 / 退款 / UNKNOWN 收敛 / 每日对账）"
