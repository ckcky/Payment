#!/usr/bin/env bash
# =============================================================================
# start-tunnel.sh — 本地联调：把宿主 payment-service 暴露到公网，供支付宝沙箱回调
#
# 为什么需要它（spec 030 / FR-103 / Q5）：
#   notify 是资金事实的唯一权威来源。支付宝沙箱在公网，回调 MUST 能打到本机
#   POST /internal/channels/alipay/notify；否则**即使买家付款成功**，支付单也会
#   一直停 PROCESSING（INV-6：渠道受理 ≠ 买家已付款）。详见 runbook §4.4。
#
# 用法：
#   bash deployment/demo/start-tunnel.sh                # 前台常驻（推荐，Ctrl-C 停止）
#   bash deployment/demo/start-tunnel.sh 8084           # 显式指定被穿透的端口
#   NGROK_DOMAIN=xxx.ngrok-free.dev bash deployment/demo/start-tunnel.sh
#
# 本机已知坑（踩过，别再踩）：
#   1. **必须 unset 代理变量**。ngrok 免费版把「agent 走 http/s 代理」当作付费功能，
#      检测到 http_proxy/https_proxy 会直接失败：ERR_NGROK_9009。
#   2. **必须前台阻塞运行，不能 nohup ... &**。沙箱环境会在命令结束时回收子进程，
#      后台化的 ngrok 会被静默杀掉——表现为隧道"启动成功"但公网立刻返回
#      ngrok 的 ERR_NGROK_3200（endpoint offline）。
#   3. ngrok 3.39 起 `--domain` 已废弃，用 `--url`。
# =============================================================================
set -u

PORT="${1:-8084}"
DOMAIN="${NGROK_DOMAIN:-chance-eligibly-mutiny.ngrok-free.dev}"
NGROK_BIN="${NGROK_BIN:-ngrok}"

if ! command -v "$NGROK_BIN" >/dev/null 2>&1; then
  echo "✗ 找不到 ngrok（可执行文件不在 PATH）。" >&2
  echo "  安装：brew install ngrok   或从 https://ngrok.com/download 下载" >&2
  exit 1
fi

echo "==> 被穿透端口：${PORT}（宿主 payment-service）"
echo "==> 固定域名  ：https://${DOMAIN}"
echo "==> 回调地址  ：https://${DOMAIN}/internal/channels/alipay/notify"
echo
echo "    请确认 payment-service 的环境变量与之匹配："
echo "      export PAYMENT_CHANNEL_NOTIFY_URL=https://${DOMAIN}/internal/channels/alipay/notify"
echo "      export PAYMENT_CHANNEL_RETURN_URL=https://${DOMAIN}/cashier/return"
echo
echo "    验证隧道是否真通（返回 403 = 已打到 payment-service，空报文验签必然失败，这是对的）："
echo "      curl -s --noproxy '*' -X POST -d 'out_trade_no=probe' \\"
echo "        https://${DOMAIN}/internal/channels/alipay/notify"
echo
echo "==> 启动隧道（前台常驻，Ctrl-C 停止）..."
echo

# 坑 1：免费版禁止 agent 走 http/s 代理
unset http_proxy https_proxy all_proxy no_proxy \
      HTTP_proxy HTTPS_proxy ALL_proxy NO_proxy \
      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY

# 坑 2：必须前台阻塞（不要 & / nohup），否则子进程随命令结束被回收
exec "$NGROK_BIN" http "$PORT" --url="https://${DOMAIN}" --log=stdout
