#!/usr/bin/env node
/**
 * order-idempotency-verify.js —— 分布式下单入口幂等验证（ADR-0039/0040/012，R3 runbook ②）
 *
 * 并发 N 个携带相同 Idempotency-Key 的 POST /orders：
 *   预期恰好 1×201（PROCEED），其余 409（CONFLICT）且带 Retry-After: 1，不重复下单；
 *   全部完成后同 key 重放 → 200 且订单号与首次一致（不重建对象）。
 *
 * 环境变量（均可选）：
 *   BASE_URL   默认 http://localhost:8083（order-service）
 *   CONCURRENCY 默认 50
 *   SKU_ID / MERCHANT_ID / USER_ID / QUANTITY  下单参数
 *   OUT        结果 JSON 输出路径（可选）
 *
 * 前置：目标 SKU 需有 DB 库存与（若已播种）充足的秒杀配额，否则首个请求会因
 *       库存/准入失败而非 201——那不是幂等层的失败，但会掩盖断言，注意区分。
 */
'use strict';
const http = require('http');
const { performance } = require('perf_hooks');
const fs = require('fs');

const BASE = process.env.BASE_URL || 'http://localhost:8083';
const N = parseInt(process.env.CONCURRENCY || '50', 10);
const SKU_ID = process.env.SKU_ID || '2';
const MERCHANT_ID = process.env.MERCHANT_ID || '1';
const USER_ID = process.env.USER_ID || 'u-r3-idem';
const QUANTITY = process.env.QUANTITY || '1';
const OUT = process.env.OUT || '';
const KEY = process.env.KEY || `r3-idem-${Date.now()}`;

const m = BASE.match(/^http:\/\/([^:/]+)(?::(\d+))?/);
const HOST = m[1], PORT = parseInt(m[2] || '80', 10);
const BODY = JSON.stringify({
  userId: USER_ID, merchantId: MERCHANT_ID, items: [{ skuId: parseInt(SKU_ID, 10), quantity: parseInt(QUANTITY, 10) }],
});

function post(key) {
  return new Promise((resolve) => {
    const t0 = performance.now();
    const req = http.request(
      {
        host: HOST, port: PORT, method: 'POST', path: '/orders',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(BODY), 'Idempotency-Key': key },
        timeout: 10000,
      },
      (res) => {
        let data = '';
        res.on('data', (c) => { data += c; });
        res.on('end', () => resolve({
          status: res.statusCode,
          retryAfter: res.headers['retry-after'] || null,
          body: data,
          dur: performance.now() - t0,
        }));
      }
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (err) => resolve({ status: 0, retryAfter: null, body: String(err), dur: performance.now() - t0 }));
    req.end(BODY);
  });
}

(async () => {
  console.log(`==> 并发 ${N} 个相同 Idempotency-Key=${KEY} 的 POST /orders`);
  const results = await Promise.all(Array.from({ length: N }, () => post(KEY)));

  const byStatus = {};
  for (const r of results) byStatus[r.status] = (byStatus[r.status] || 0) + 1;
  const created = results.filter((r) => r.status === 201);
  const conflicts = results.filter((r) => r.status === 409);
  const conflictWithRetryAfter = conflicts.filter((r) => r.retryAfter !== null).length;
  // 非 201/409 的失败请求：记录响应体便于定位（如冷启动 LoadBalancer/连接池竞争）
  const failures = results
    .filter((r) => r.status !== 201 && r.status !== 409)
    .map((r) => ({ status: r.status, body: r.body.slice(0, 300) }));

  let replay = null;
  if (created.length > 0) {
    // 等待限流窗口（order.ratelimit 窗口 1s）翻转，避免重放请求被 429 干扰幂等断言
    await new Promise((r) => setTimeout(r, 1600));
    const r = await post(KEY); // 同 key 重放：应为 200 + 原响应
    replay = { status: r.status, sameOrderId: r.status === 200 && r.body === created[0].body };
  }

  const pass =
    created.length === 1 &&
    conflicts.length === N - 1 &&
    conflictWithRetryAfter === conflicts.length &&
    (replay === null || (replay.status === 200 && replay.sameOrderId));

  const summary = {
    key: KEY, concurrency: N, by_status: byStatus,
    created: created.length, conflict: conflicts.length,
    conflict_with_retry_after: conflictWithRetryAfter,
    failures,
    replay,
    order_id: created.length ? safeParse(created[0].body) : null,
    pass,
  };
  console.log(JSON.stringify(summary, null, 2));
  if (OUT) fs.writeFileSync(OUT, JSON.stringify(summary, null, 2));
  process.exitCode = pass ? 0 : 1;
})();

function safeParse(s) { try { const j = JSON.parse(s); return j.id !== undefined ? j.id : j; } catch (e) { return s.slice(0, 120); } }
