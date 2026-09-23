#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Stripe webhook 本地转发（渠道插件化 / STRIPE-01 配套工具）
#
# 作用：Stripe 在云端，你的 payment-service 在本机，云端无法直连 localhost。
# Stripe CLI 会在 Stripe 侧注册一个临时端点，把事件转发到本机。
# 终端第一行打印的 whsec_... 就是 webhook 签名密钥。
#
# 用法（Git Bash）：
#   export PAYMENT_STRIPE_SANDBOX_SECRET_KEY=sk_test_...
#   bash deployment/demo/stripe-listen.sh
#
# 前置：Stripe CLI 已装到 ~/.workbuddy/binaries/stripe/stripe.exe
#       没装的话本脚本会自动下载（Windows x86_64）。
# ---------------------------------------------------------------------------
set -euo pipefail

PORT="${PAYMENT_SERVICE_PORT:-8084}"
CALLBACK_PATH="/internal/channels/STRIPE/callback"
STRIPE_DIR="${HOME}/.workbuddy/binaries/stripe"
STRIPE_BIN="${STRIPE_DIR}/stripe.exe"
CLI_VERSION="v1.51.1"

if [[ -z "${PAYMENT_STRIPE_SANDBOX_SECRET_KEY:-}" ]]; then
  echo "[stripe-listen] 缺少环境变量 PAYMENT_STRIPE_SANDBOX_SECRET_KEY（sk_test_...）" >&2
  echo "  先执行：export PAYMENT_STRIPE_SANDBOX_SECRET_KEY=sk_test_xxx" >&2
  exit 1
fi

if [[ ! -f "${STRIPE_BIN}" ]]; then
  echo "[stripe-listen] 未找到 Stripe CLI，开始下载 ${CLI_VERSION} ..."
  mkdir -p "${STRIPE_DIR}"
  curl -sL -o "${STRIPE_DIR}/stripe.zip" \
    "https://github.com/stripe/stripe-cli/releases/download/${CLI_VERSION}/stripe_${CLI_VERSION#v}_windows_x86_64.zip"
  (cd "${STRIPE_DIR}" && unzip -o stripe.zip && rm -f stripe.zip)
fi

echo "[stripe-listen] 转发目标：${CALLBACK_PATH} -> http://localhost:${PORT}${CALLBACK_PATH}"
echo "[stripe-listen] 下方打印的 whsec_... 请作为 PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET"
echo "[stripe-listen] Ctrl+C 停止"
echo

STRIPE_DEVICE_NAME=paymentarch \
  "${STRIPE_BIN}" listen \
    --api-key "${PAYMENT_STRIPE_SANDBOX_SECRET_KEY}" \
    --forward-to "localhost:${PORT}${CALLBACK_PATH}"
