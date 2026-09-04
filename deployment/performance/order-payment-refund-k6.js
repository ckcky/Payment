/**
 * order-payment-refund-k6.js —— 全链路业务压测（下单 → 渠道回调 → 退款）
 *
 * 每个 VU 迭代执行完整业务闭环（收银台路径，覆盖 order/catalog/payment/mock-channel/refund
 * 五个服务的写路径 + Feign 内部 RPC 链）：
 *   ① POST /orders                          order-service    （幂等创建；内部串联秒杀准入→库存预占
 *                                                              →支付意图创建，返回 PROCESSING + payUrl）
 *   ② POST /mock-channel/callback           mock-channel-web （以渠道身份 HMAC 签名转发 payment，
 *                                                              驱动 PROCESSING→SUCCEEDED）
 *   ③ POST /internal/refunds                payment-service（refund 包，ADR-0064）
 *                                                              （创建退款，进程内渠道退款，同步 SUCCEEDED）
 *   ④ POST /internal/refunds/{id}/resolve   payment-service  （权威确认端点，幂等收敛）
 *
 * 环境变量：
 *   VUS        并发虚拟用户数（默认 20）
 *   DURATION   稳态持续时间（默认 2m；另有 30s 爬坡与 15s 退出）
 *   SKU_ID / MERCHANT_ID    下单参数（必须指向库存充足且已激活的 SKU；秒杀配额需充足）
 *   ORDER_URL / MOCK_URL / REFUND_URL  各服务地址
 *   OUT        末尾摘要 JSON 输出路径（可选）
 */
'use strict';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const VUS = parseInt(__ENV.VUS || '20', 10);
const DURATION = __ENV.DURATION || '2m';
const SKU_ID = parseInt(__ENV.SKU_ID || '4', 10);
const MERCHANT_ID = __ENV.MERCHANT_ID || '1';
const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8083';
const MOCK_URL = __ENV.MOCK_URL || 'http://localhost:8091';
const REFUND_URL = __ENV.REFUND_URL || 'http://localhost:8084';  // refund 端点已并入 payment-service（ADR-0064）
const OUT = __ENV.OUT || '';

const stageOrder = new Trend('chain_order_create', true);
const stageCallback = new Trend('chain_channel_callback', true);
const stageRefund = new Trend('chain_refund_create', true);
const stageResolve = new Trend('chain_refund_resolve', true);
const chainOk = new Counter('chain_completed_total');

export const options = {
  scenarios: {
    full_chain: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
  discardResponseBodies: false,
};

export default function () {
  const uid = `load-${__VU}-${__ITER}-${Date.now()}`;
  const userId = 'u-load';

  // ① 下单（幂等键=uid）：order-service 内部串联 秒杀准入 → 库存预占 → 支付意图（PROCESSING+payUrl）
  const orderBody = JSON.stringify({
    userId, merchantId: MERCHANT_ID,
    items: [{ skuId: SKU_ID, quantity: 1 }],
  });
  const t0 = Date.now();
  const rOrder = http.post(`${ORDER_URL}/orders`, orderBody, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uid },
    tags: { name: 'order_create' }, timeout: '15s',
  });
  stageOrder.add(Date.now() - t0);
  if (!check(rOrder, { 'order 201': (r) => r.status === 201 })) { sleep(1); return; }
  const order = rOrder.json();

  // ② 渠道回调：mock-channel-web 以渠道身份 HMAC 签名转发 payment，驱动 PROCESSING→SUCCEEDED
  const cbBody = JSON.stringify({
    paymentNo: order.paymentNo, status: 'SUCCESS',
    channelReference: `ref-${uid}`, amountMinor: order.totalMinor, signMode: 'VALID',
  });
  const t2 = Date.now();
  const rCb = http.post(`${MOCK_URL}/mock-channel/callback`, cbBody, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'channel_callback' }, timeout: '15s',
  });
  stageCallback.add(Date.now() - t2);
  if (!check(rCb, { 'callback 200': (r) => r.status === 200 })) { sleep(1); return; }

  // ③ 创建退款：payment-service（refund 包）渠道退款（同步 SUCCEEDED）
  const refundBody = JSON.stringify({
    orderNo: order.orderNo, paymentNo: order.paymentNo, userId,
    amountMinor: order.totalMinor, currencyCode: order.currencyCode,
    reason: 'customer', idempotencyKey: `refund-${uid}`, items: null,
  });
  const t3 = Date.now();
  const rRefund = http.post(`${REFUND_URL}/internal/refunds`, refundBody, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'refund_create' }, timeout: '15s',
  });
  stageRefund.add(Date.now() - t3);
  if (!check(rRefund, { 'refund ok': (r) => r.status === 200 || r.status === 201 })) { sleep(1); return; }
  const refund = rRefund.json();

  // ④ 退款权威确认（幂等收敛端点）
  const t4 = Date.now();
  const rResolve = http.post(`${REFUND_URL}/internal/refunds/${refund.id}/resolve`,
    JSON.stringify({ status: 'SUCCEEDED' }), {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'refund_resolve' }, timeout: '15s',
    });
  stageResolve.add(Date.now() - t4);
  if (!check(rResolve, { 'refund resolve ok': (r) => r.status === 200 })) { sleep(1); return; }

  chainOk.add(1);
  sleep(0.2);
}

export function handleSummary(data) {
  const m = data.metrics;
  const summary = {
    vus: VUS, duration: DURATION,
    iterations: m.iterations ? m.iterations.count : 0,
    chain_completed: m.chain_completed_total ? m.chain_completed_total.count : 0,
    http_req_failed_rate: m.http_req_failed ? m.http_req_failed.rate : null,
    checks_pass_rate: m.checks ? m.checks.rate : null,
    p50_ms: m.http_req_duration ? m.http_req_duration['p(50)'] : null,
    p95_ms: m.http_req_duration ? m.http_req_duration['p(95)'] : null,
    p99_ms: m.http_req_duration ? m.http_req_duration['p(99)'] : null,
    per_stage_p95_ms: {
      order_create: m.chain_order_create ? m.chain_order_create['p(95)'] : null,
      channel_callback: m.chain_channel_callback ? m.chain_channel_callback['p(95)'] : null,
      refund_create: m.chain_refund_create ? m.chain_refund_create['p(95)'] : null,
      refund_resolve: m.chain_refund_resolve ? m.chain_refund_resolve['p(95)'] : null,
    },
  };
  const text = JSON.stringify(summary, null, 2);
  const result = { stdout: text };
  if (OUT) {
    result[OUT] = text;
  }
  return result;
}
