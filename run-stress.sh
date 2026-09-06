#!/usr/bin/env bash
#
# run-stress.sh —— 运行性能压测（解压后进此目录执行）
#
# 前置：先 ./run.sh 拉起全栈并等待就绪；本机需 Node.js >= 18（用于零依赖负载生成器）
# 行为：跑两套 Node 版负载生成器（catalog 缓存读+秒杀 / 全链路下单→支付→退款），
#       并生成自包含 HTML 报告。
# 说明：项目同时提供 k6 版脚本（deployment/performance/*-k6.js），需自行安装 k6；
#       本脚本默认用 Node 版，零外部二进制依赖，下载即用。
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "==> 压测前置检查"
if ! command -v node >/dev/null 2>&1; then
  echo "✗ 需要 Node.js(>=18) 运行压测负载生成器。" >&2
  exit 1
fi
if ! curl -fsS http://127.0.0.1:8848/nacos/v1/ns/operator/metrics >/dev/null 2>&1; then
  echo "✗ 未检测到 Nacos(8848)。请先运行 ./run.sh 启动全栈，等待就绪后再跑压测。" >&2
  exit 1
fi

RESULTS="deployment/performance/results"
mkdir -p "$RESULTS"

echo ""
echo "==> [1/2] catalog 缓存读 + 秒杀压测 (Node 零依赖负载生成器)"
BASE_URL=http://localhost:8082 SKU_ID=1 SECKILL_SKU_ID=1 \
  OUT="$RESULTS/stress-catalog-load.json" \
  node deployment/performance/catalog-seckill-loadgen.js

echo ""
echo "==> [2/2] 全链路 下单→支付→退款 压测"
VUS=20 DURATION=90s SKU_ID=1 ORDER_RATE=40 SURPLUS_RATIO=0.2 \
  OUT="$RESULTS/stress-chain-load.json" \
  node deployment/performance/order-payment-refund-loadgen.js

echo ""
echo "==> 生成 HTML 报告"
RESULT="$RESULTS/stress-chain-load.json" \
  OUT="$RESULTS/stress-chain-perf-report.html" \
  node deployment/performance/generate-report.js

echo ""
echo "压测完成。"
echo "  链路报告 : $RESULTS/stress-chain-perf-report.html"
echo "  原始数据 : $RESULTS/stress-catalog-load.json"
echo "             $RESULTS/stress-chain-load.json"
echo "  注：k6 版脚本见 deployment/performance/*-k6.js（需自行安装 k6）"
