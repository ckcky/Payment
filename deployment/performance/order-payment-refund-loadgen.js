/**
 * order-payment-refund-loadgen.js —— 全链路业务压测（Node stdlib 版，k6 不可用时的等价复刻）
 *
 * 复刻 order-payment-refund-k6.js 的业务闭环（order/catalog/payment/mock-channel-web/refund
 * 五个服务的写路径 + Feign 内部 RPC 链），并新增 Feature 016 / ADR-0054 的 surplus 分支：
 *   ① POST /orders                       order-service（幂等创建）
 *   ② POST /orders/{orderNo}/payments     order-service（显式选渠道建支付单）
 *   ③ POST /mock-channel/callback         mock-channel-web（HMAC 签名回调 → SUCCEEDED）
 *   ④ POST /internal/refunds              payment-service（全额退款，同步 SUCCEEDED）
 *   ⑤ POST /internal/refunds/{refundNo}/resolve  payment-service（幂等收敛）
 *   ⑥ [surplus 分支, 按 SURPLUS_RATIO 概率] 再建第二张支付单并回调 SUCCESS：
 *      断言返回 200（**绝不 409 ORDER_NOT_PAYABLE**）且触发 order 层自动退款
 *      （payment_auto_refund_* 指标，Grafana「自动退款 · surplus 补偿」面板）。
 *
 * 环境变量：
 *   VUS            并发协程数（默认 20）
 *   DURATION       稳态时长，支持 "90s" / "2m" / 纯秒数（默认 2m）
 *   SKU_ID / MERCHANT_ID / SURPLUS_RATIO(默认 0.2)
 *   ORDER_URL / MOCK_URL / REFUND_URL / OUT
 */
'use strict';

const http = require('http');
const fs = require('fs');

const VUS = parseInt(env('VUS', '20'), 10);
const DURATION = parseDuration(env('DURATION', '2m'));
const SKU_ID = parseInt(env('SKU_ID', '5'), 10);
const MERCHANT_ID = env('MERCHANT_ID', '1');
const SURPLUS_RATIO = parseFloat(env('SURPLUS_RATIO', '0.2'));
const ORDER_URL = env('ORDER_URL', 'http://localhost:8083');
const MOCK_URL = env('MOCK_URL', 'http://localhost:8091');
const REFUND_URL = env('REFUND_URL', 'http://localhost:8084');
const OUT = env('OUT', '');
// 全局下单速率配平（单/秒）：order-service /orders 固定窗口限流 capacity=50/1s（ADR-0046），
// 默认 40 留 20% 余量，避免 429 噪声淹没延迟分位。0 = 不限速。
const ORDER_RATE = parseFloat(env('ORDER_RATE', '40'));

function env(k, d) { return process.env[k] !== undefined ? process.env[k] : d; }
function parseDuration(s) {
  const m = /^(\d+)([sm]?)$/.exec(String(s).trim());
  if (!m) return 120000;
  const n = parseInt(m[1], 10);
  return m[2] === 'm' ? n * 60000 : m[2] === 's' ? n * 1000 : n * 1000;
}

// keep-alive agent（每目标host各一）
const agents = new Map();
function agentFor(url) {
  const u = new URL(url);
  const key = u.origin;
  if (!agents.has(key)) {
    agents.set(key, new http.Agent({ keepAlive: true, maxSockets: VUS * 2 }));
  }
  return agents.get(key);
}

function request(method, url, body, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const payload = body === undefined ? null : Buffer.from(body, 'utf8');
    const req = http.request({
      host: u.hostname, port: u.port || 80, path: u.pathname + u.search, method,
      headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
      agent: agentFor(url),
      timeout: 15000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

// ---- 统计 ----
const stages = {
  order_create: [], payment_create: [], channel_callback: [],
  refund_create: [], refund_resolve: [], surplus_auto_refund: [],
};
const stats = {
  iterations: 0, chain_completed: 0, surplus_runs: 0,
  errors: {}, status_codes: {},
};
function recordError(tag) { stats.errors[tag] = (stats.errors[tag] || 0) + 1; }
function recordStatus(code) { stats.status_codes[code] = (stats.status_codes[code] || 0) + 1; }
function pct(arr, p) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return Math.round(s[Math.min(s.length - 1, Math.floor(s.length * p))] * 10) / 10;
}

async function timed(stage, fn) {
  const t = Date.now();
  try {
    const r = await fn();
    stages[stage].push(Date.now() - t);
    recordStatus(r.status);
    return r;
  } catch (e) {
    recordError(stage + ':net-' + (e.message || 'error'));
    return { status: 0, body: '' };
  }
}

async function chainOnce(workerId, iter) {
  const uid = `load-${workerId}-${iter}-${Date.now()}`;
  const userId = 'u-load';

  const rOrder = await timed('order_create', () =>
    request('POST', `${ORDER_URL}/orders`,
      JSON.stringify({ userId, merchantId: MERCHANT_ID, items: [{ skuId: SKU_ID, quantity: 1 }] }),
      { 'Idempotency-Key': uid }));
  if (rOrder.status !== 201) { recordError('order_create:' + rOrder.status); return; }
  const order = JSON.parse(rOrder.body);

  const rPay = await timed('payment_create', () =>
    request('POST', `${ORDER_URL}/orders/${order.orderNo}/payments`,
      JSON.stringify({ channelCode: 'alipay' })));
  if (rPay.status !== 201) { recordError('payment_create:' + rPay.status); return; }
  const payment = JSON.parse(rPay.body);

  // Feature 016 surplus 分支前置：第二张支付单必须在订单仍 PENDING_PAYMENT 时创建
  // （015 语义：订单 PAID 后建单会被 409 ORDER_NOT_PAYABLE 拒绝——这是建单侧保护，
  //  surplus 判定发生在「两张已存在支付单的回调都成功」时，ADR-0054）。
  let pay2 = null;
  if (Math.random() < SURPLUS_RATIO) {
    stats.surplus_runs++;
    const rPay2 = await request('POST', `${ORDER_URL}/orders/${order.orderNo}/payments`,
      JSON.stringify({ channelCode: 'wechat' }));
    if (rPay2.status !== 201) { recordError('surplus_payment_create:' + rPay2.status); return; }
    pay2 = JSON.parse(rPay2.body);
  }

  const rCb = await timed('channel_callback', () =>
    request('POST', `${MOCK_URL}/mock-channel/callback`,
      JSON.stringify({ paymentNo: payment.paymentNo, status: 'SUCCESS',
        channelReference: `ref-${uid}`, amountMinor: order.totalMinor, signMode: 'VALID' })));
  if (rCb.status !== 200) { recordError('channel_callback:' + rCb.status); return; }

  // 第二张支付单回调成功 → order transaction 层判 surplus → 自动退款。
  // 硬断言：回调必须 200（order 对 surplus 绝不 409，FR-007 / SC-001）。
  if (pay2) {
    const rCb2 = await timed('surplus_auto_refund', () =>
      request('POST', `${MOCK_URL}/mock-channel/callback`,
        JSON.stringify({ paymentNo: pay2.paymentNo, status: 'SUCCESS',
          channelReference: `ref2-${uid}`, amountMinor: order.totalMinor, signMode: 'VALID' })));
    if (rCb2.status === 409) { recordError('HARD_FAIL_surplus_409'); return; }
    if (rCb2.status !== 200) { recordError('surplus_callback:' + rCb2.status); return; }
  }

  const rRefund = await timed('refund_create', () =>
    request('POST', `${REFUND_URL}/internal/refunds`,
      JSON.stringify({ orderNo: order.orderNo, paymentNo: payment.paymentNo, userId,
        amountMinor: order.totalMinor, currencyCode: order.currencyCode,
        reason: 'customer', idempotencyKey: `refund-${uid}`, items: null })));
  if (rRefund.status !== 200 && rRefund.status !== 201) { recordError('refund_create:' + rRefund.status); return; }
  const refund = JSON.parse(rRefund.body);

  const rResolve = await timed('refund_resolve', () =>
    request('POST', `${REFUND_URL}/internal/refunds/${refund.refundNo}/resolve`,
      JSON.stringify({ status: 'SUCCEEDED' })));
  if (rResolve.status !== 200) { recordError('refund_resolve:' + rResolve.status); return; }

  stats.chain_completed++;
}

async function worker(workerId, deadline) {
  let iter = 0;
  while (Date.now() < deadline) {
    if (ORDER_RATE > 0) await acquireSlot();
    stats.iterations++;
    try { await chainOnce(workerId, iter++); } catch (e) { recordError('unexpected:' + (e.message || e)); }
  }
}

// 全局速率配平：按 ORDER_RATE 均匀发放时间槽（跨 worker 共享）
let nextSlot = Date.now();
const slotInterval = ORDER_RATE > 0 ? 1000 / ORDER_RATE : 0;
async function acquireSlot() {
  const now = Date.now();
  if (now < nextSlot) {
    const wait = nextSlot - now;
    // 系统休眠/时钟跳变鲁棒：等待超过 5s 视为配平失效，直接放行并重置基准
    if (wait > 5000) { nextSlot = now + slotInterval; return; }
    await new Promise((r) => setTimeout(r, wait));
  }
  nextSlot = Math.max(nextSlot, Date.now()) + slotInterval;
}

(async () => {
  const startedAt = new Date().toISOString();
  const t0 = Date.now();
  console.log(`==> order-payment-refund 压测：VUS=${VUS} DURATION=${DURATION / 1000}s SKU=${SKU_ID} SURPLUS_RATIO=${SURPLUS_RATIO}`);
  const workers = [];
  for (let i = 0; i < VUS; i++) workers.push(worker(i, t0 + DURATION));
  await Promise.all(workers);

  const elapsed = Date.now() - t0;
  const summary = {
    meta: {
      generated_at: startedAt, tool: 'node-stdlib-loadgen (k6 unavailable: binary download blocked by proxy)',
      vus: VUS, duration_ms: elapsed, sku_id: SKU_ID, merchant_id: MERCHANT_ID,
      surplus_ratio: SURPLUS_RATIO, order_rate: ORDER_RATE, feature: '016 / ADR-0054 verification',
    },
    throughput: {
      iterations: stats.iterations,
      chain_completed: stats.chain_completed,
      surplus_runs: stats.surplus_runs,
      rps: Math.round((stats.iterations / elapsed) * 1000 * 100) / 100,
      completed_rps: Math.round((stats.chain_completed / elapsed) * 1000 * 100) / 100,
    },
    latency_ms: Object.fromEntries(Object.entries(stages).map(([k, v]) => [
      k, { count: v.length, p50: pct(v, 0.5), p95: pct(v, 0.95), p99: pct(v, 0.99), max: v.length ? Math.max(...v) : null },
    ])),
    status_codes: stats.status_codes,
    errors: stats.errors,
    error_count: Object.values(stats.errors).reduce((a, b) => a + b, 0),
  };
  console.log(JSON.stringify(summary, null, 2));
  if (OUT) { fs.writeFileSync(OUT, JSON.stringify(summary, null, 2)); console.log('written:', OUT); }
  process.exit(Object.keys(stats.errors).some((k) => k.startsWith('HARD_FAIL')) ? 2 : 0);
})();
