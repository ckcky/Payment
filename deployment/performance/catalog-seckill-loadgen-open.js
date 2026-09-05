#!/usr/bin/env node
/**
 * catalog-seckill-loadgen-open.js —— 开环（open-loop）恒定到达率压测驱动，Node 标准库实现。
 *
 * 为什么需要它：
 *   原 catalog-seckill-loadgen.js 是**闭环**模型：每个 VU 串行执行「发请求 → 等响应 → sleep(50ms)」。
 *   在 500 VU 下单进程 Node 事件循环严重拥塞，响应完成回调与定时器回调相互排队，
 *   测得的 p95 会被客户端自身排队时间污染（Little's Law：500 VU / 1108 RPS ≈ 451ms
 *   ≈ 实测 p95 415ms，即吞吐被并发/延迟公式锁死，而非服务端处理慢）。
 *   开环模型按固定速率发请求，不等待上一请求完成，测得的延迟才是服务端真实水平。
 *
 * 用法：
 *   BASE_URL=http://localhost:8082 RATE=1000 DURATION=30 METHOD=POST \
 *   REQ_PATH=/internal/stock/seckill/deduct?skuId=1\&quantity=1 \
 *   OUT=results/open-result.json \
 *   node deployment/performance/catalog-seckill-loadgen-open.js
 *
 * 环境变量：
 *   BASE_URL  目标地址（默认 http://localhost:8082）
 *   RATE      目标到达率（请求/秒，默认 1000）
 *   DURATION  持续时长（秒，默认 30）
 *   METHOD    HTTP 方法（默认 GET）
 *   REQ_PATH   请求路径（含 query，默认 /actuator/health）—— 不能用 PATH，会覆盖 shell 的 PATH
 *   WARMUP    预热时长（秒，不计入统计，默认 5）
 *   OUT       结果输出路径
 *   TICK_MS   调度节拍（毫秒，默认 5）
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { performance } = require('perf_hooks');

const BASE = process.env.BASE_URL || 'http://localhost:8082';
const RATE = parseInt(process.env.RATE || '1000', 10);
const DURATION = parseInt(process.env.DURATION || '30', 10);
const METHOD = process.env.METHOD || 'GET';
// 注意：不可用 PATH 作为变量名——会覆盖 shell 自身的 PATH 环境变量导致命令无法执行。
const REQ_PATH = process.env.REQ_PATH || '/actuator/health';
const WARMUP = parseInt(process.env.WARMUP || '5', 10);
const TICK_MS = parseInt(process.env.TICK_MS || '5', 10);
const OUT = process.env.OUT || path.join(__dirname, 'results', 'open-result.json');

function parseBase(u) {
  const m = u.match(/^https?:\/\/([^:/]+)(?::(\d+))?/);
  return { host: m[1], port: parseInt(m[2] || (u.startsWith('https') ? 443 : 80), 10) };
}
const T = parseBase(BASE);
const agent = new http.Agent({ keepAlive: true, maxSockets: 4096, keepAliveMsecs: 30000 });

function doRequest() {
  return new Promise((resolve) => {
    const t0 = performance.now();
    const req = http.request(
      { host: T.host, port: T.port, method: METHOD, path: REQ_PATH, agent, timeout: 15000 },
      (res) => {
        let n = 0;
        res.on('data', (c) => { n += c.length; });
        res.on('end', () => resolve({ dur: performance.now() - t0, status: res.statusCode, ok: true }));
      }
    );
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    req.on('error', (err) => resolve({ dur: performance.now() - t0, status: 0, ok: false, error: err.code || String(err) }));
    req.end();
  });
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
  return sorted[idx];
}

function summarize(records, elapsedMs) {
  const total = records.length;
  const durations = records.map((r) => r.dur).sort((a, b) => a - b);
  const statusHist = {};
  let errors = 0;
  for (const r of records) {
    const key = r.ok ? String(r.status) : ('ERR:' + (r.error || 'unknown'));
    statusHist[key] = (statusHist[key] || 0) + 1;
    if (!r.ok || r.status >= 500) errors++;
  }
  const lat = (p) => +percentile(durations, p).toFixed(2);
  return {
    total,
    rps: +(total / (elapsedMs / 1000)).toFixed(2),
    duration_ms: Math.round(elapsedMs),
    status_hist: statusHist,
    errors,
    error_rate: +(total ? errors / total : 0).toFixed(4),
    latency_ms: {
      avg: +(durations.reduce((a, b) => a + b, 0) / (total || 1)).toFixed(2),
      min: lat(0), p50: lat(0.5), p90: lat(0.9), p95: lat(0.95), p99: lat(0.99),
      max: +durations[durations.length - 1].toFixed(2),
    },
    sla: { 'p95<50ms': lat(0.95) < 50, 'p99<120ms': lat(0.99) < 120 },
  };
}

(async () => {
  console.log(`开环压测: ${METHOD} ${BASE}${REQ_PATH}`);
  console.log(`目标到达率=${RATE} req/s 预热=${WARMUP}s 时长=${DURATION}s 节拍=${TICK_MS}ms`);

  // 预热（不统计）
  let inFlight = [];
  const warmEnd = performance.now() + WARMUP * 1000;
  let sent = 0;
  const perTick = Math.max(1, Math.round((RATE * TICK_MS) / 1000));
  while (performance.now() < warmEnd) {
    for (let i = 0; i < perTick; i++) inFlight.push(doRequest());
    sent += perTick;
    await new Promise((r) => setTimeout(r, TICK_MS));
  }
  await Promise.all(inFlight);
  inFlight = [];
  console.log(`预热完成（约 ${sent} 请求）`);
  sent = 0; // 重置计数：测量阶段的 toSend 按「测量起点」计算，若沿用预热计数会恒为 0

  // 正式测量
  const records = [];
  let maxInFlight = 0;
  const t0 = performance.now();
  const end = t0 + DURATION * 1000;
  const tickState = { next: t0, deficit: 0 };

  while (performance.now() < end) {
    const now = performance.now();
    // 按真实经过时间补齐应发请求数（补偿漂移，保证恒定到达率）
    const expected = ((now - t0) / 1000) * RATE + tickState.deficit;
    const toSend = Math.max(0, Math.floor(expected) - sent);
    for (let i = 0; i < toSend; i++) {
      const p = doRequest().then((r) => { records.push(r); });
      inFlight.push(p);
    }
    sent += toSend;
    maxInFlight = Math.max(maxInFlight, inFlight.length);
    // 清理已完成
    if (inFlight.length > 4096) {
      await Promise.race([Promise.all(inFlight), new Promise((r) => setTimeout(r, 0))]);
      inFlight = inFlight.filter(() => true);
    }
    await new Promise((r) => setTimeout(r, TICK_MS));
  }
  await Promise.all(inFlight);
  const elapsed = performance.now() - t0;

  const result = {
    meta: {
      generated_at: new Date().toISOString(),
      model: 'open-loop (constant arrival rate)',
      base_url: BASE, method: METHOD, path: REQ_PATH,
      target_rate_rps: RATE, warmup_s: WARMUP, duration_s: DURATION, tick_ms: TICK_MS,
      peak_in_flight: maxInFlight,
    },
    ...summarize(records, elapsed),
  };
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  const s = result;
  console.log(`\n结果: 总请求=${s.total} 实际RPS=${s.rps} 错误率=${s.error_rate} 峰值并发=${maxInFlight}`);
  console.log(`延迟: p50=${s.latency_ms.p50}ms p90=${s.latency_ms.p90}ms p95=${s.latency_ms.p95}ms p99=${s.latency_ms.p99}ms max=${s.latency_ms.max}ms`);
  console.log(`SLO: p95<50ms=${s.sla['p95<50ms']}  p99<120ms=${s.sla['p99<120ms']}`);
  console.log(`状态码分布: ${JSON.stringify(s.status_hist)}`);
  console.log(`\n结果已写入: ${OUT}`);
})();
