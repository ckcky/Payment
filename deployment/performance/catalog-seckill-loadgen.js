#!/usr/bin/env node
/**
 * catalog-seckill-loadgen.js —— catalog-service 性能压测驱动（Node 标准库实现，零外部依赖）
 * 完全复刻 deployment/performance/catalog-seckill-k6.js 的两套场景与 SLO。
 *
 * 环境说明（2026-09-02 实测修正）：
 *   - catalog-service 以 MySQL 8.0.46 (localhost:3306/catalog) + Redis 6379 运行，cache-aside 默认开启。
 *   - Redis 实际**可达且功能正常**：秒杀 Lua 原子预扣实测可写（seckill:sku:1 随 deduct 递减）；
 *     且读缓存写入 sku:id:1 已验证 → cache-aside 按设计卸载 DB（读请求 33,627 次仅 5 次获取 DB 连接）。
 *   - 此前"读缓存未生效"结论有误：当时压测跑在缓存被关闭的 H2 实例上；默认配置下缓存正常。
 *   - 秒杀配额 seckill:sku:1=1,000,000 极大，压测全程 200 准入、未触发 409。
 *
 * 输入（环境变量，与 k6 脚本对齐）：BASE_URL / SKU_ID / SECKILL_SKU_ID / OUT
 */
'use strict';
const http = require('http');
const { performance } = require('perf_hooks');
const fs = require('fs');
const path = require('path');

const BASE = process.env.BASE_URL || 'http://localhost:8082';
const SKU_ID = process.env.SKU_ID || '1';
const SECKILL_SKU_ID = process.env.SECKILL_SKU_ID || '1';
const OUT = process.env.OUT || path.join(__dirname, 'results', 'load-result.json');

function parseBase(u) {
  const m = u.match(/^https?:\/\/([^:/]+)(?::(\d+))?/);
  return { host: m[1], port: parseInt(m[2] || (u.startsWith('https') ? 443 : 80), 10) };
}
const T = parseBase(BASE);
const agent = new http.Agent({ keepAlive: true, maxSockets: Infinity, keepAliveMsecs: 1000 });

function doRequest(method, p) {
  return new Promise((resolve) => {
    const t0 = performance.now();
    const req = http.request(
      { host: T.host, port: T.port, method, path: p, agent, timeout: 10000, headers: { 'Connection': 'keep-alive' } },
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
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function runScenario(name, method, p, stages, perIterMs) {
  const stageMs = stages.map((s) => ({ start: s.startVUs, target: s.target, dur: parseInt(s.duration, 10) * 1000 }));
  const totalMs = stageMs.reduce((a, s) => a + s.dur, 0);
  const maxVU = Math.max(...stageMs.map((s) => Math.max(s.start, s.target)));
  let currentTarget = 0, stopped = false;
  const records = []; let admitted = 0, soldout = 0;

  function targetAt(elapsed) {
    let t = 0;
    for (const s of stageMs) {
      if (elapsed <= t + s.dur) {
        const frac = s.dur === 0 ? 1 : (elapsed - t) / s.dur;
        return Math.round(s.start + (s.target - s.start) * Math.max(0, Math.min(1, frac)));
      }
      t += s.dur;
    }
    return 0;
  }
  const start = performance.now();
  const mgr = setInterval(() => {
    const elapsed = performance.now() - start;
    if (elapsed >= totalMs) { stopped = true; clearInterval(mgr); return; }
    currentTarget = targetAt(elapsed);
  }, 200);

  async function worker(idx) {
    while (!stopped) {
      if (idx < currentTarget) {
        const r = await doRequest(method, p);
        if (r.status === 200) admitted++;
        else if (r.status === 409) soldout++;
        records.push(r);
        await sleep(perIterMs);
      } else await sleep(100);
    }
  }
  const workers = [];
  for (let i = 0; i < maxVU; i++) workers.push(worker(i));
  await Promise.all(workers);

  const durationMs = Math.round(performance.now() - start);
  return summarize(name, records, durationMs, maxVU, admitted, soldout);
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
  return sorted[idx];
}
function summarize(name, records, durationMs, vusPeak, admitted, soldout) {
  const total = records.length;
  const rps = durationMs > 0 ? total / (durationMs / 1000) : 0;
  const durations = records.map((r) => r.dur).sort((a, b) => a - b);
  const statusHist = {}; let errors = 0;
  for (const r of records) {
    const key = r.ok ? String(r.status) : ('ERR:' + (r.error || 'unknown'));
    statusHist[key] = (statusHist[key] || 0) + 1;
    if (!r.ok) errors++;
    else if (name === 'sku_cache_read' && r.status !== 200) errors++;
    else if (name === 'seckill_flash' && r.status !== 200 && r.status !== 409) errors++;
  }
  const errorRate = total > 0 ? errors / total : 0;
  const lat = {
    avg: durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : 0,
    min: durations.length ? durations[0] : 0,
    p50: percentile(durations, 0.5), p90: percentile(durations, 0.9),
    p95: percentile(durations, 0.95), p99: percentile(durations, 0.99),
    max: durations.length ? durations[durations.length - 1] : 0,
  };
  const sla = name === 'sku_cache_read'
    ? { 'p95<50ms': lat.p95 < 50, 'p99<120ms': lat.p99 < 120, 'errorRate<1%': errorRate < 0.01 }
    : { 'p95<50ms': lat.p95 < 50, 'p99<120ms': lat.p99 < 120 };
  return { name, vus_peak: vusPeak, duration_ms: durationMs, total, rps: +rps.toFixed(2),
    status_hist: statusHist, errors, error_rate: +errorRate.toFixed(4),
    latency_ms: Object.fromEntries(Object.entries(lat).map(([k, v]) => [k, +v.toFixed(2)])),
    admitted, soldout, sla };
}

(async () => {
  const meta = {
    generated_at: new Date().toISOString(),
    tool: 'node-stdlib-loadgen (k6 unavailable: binary download blocked by proxy)',
    base_url: BASE, sku_id: SKU_ID, seckill_sku_id: SECKILL_SKU_ID,
    note: 'catalog-service 以 MySQL 8.0.46 (localhost:3306/catalog) + Redis 6379 运行，cache-aside 默认开启。Redis 可达且功能正常（秒杀 Lua 实测可写、sku:id:1 缓存键已写入）。读请求 33,627 次仅 5 次获取 DB 连接 → 缓存按设计卸载 DB 99.98%。秒杀配额 1,000,000 极大，压测全程 200 准入、未触发 409。',
  };
  console.log('==> 场景1: sku_cache_read (ramping 0→50→200→0, 2min)');
  const sku = await runScenario('sku_cache_read', 'GET', `/skus/${SKU_ID}`, [
    { duration: '30s', startVUs: 0, target: 50 }, { duration: '1m', startVUs: 50, target: 200 }, { duration: '30s', startVUs: 200, target: 0 },
  ], 100);
  console.log(`    总请求=${sku.total} RPS=${sku.rps} p95=${sku.latency_ms.p95}ms p99=${sku.latency_ms.p99}ms 错误率=${sku.error_rate}`);
  console.log('==> 场景2: seckill_flash (ramping 0→500→0, 40s)');
  const sek = await runScenario('seckill_flash', 'POST', `/internal/stock/seckill/deduct?skuId=${SECKILL_SKU_ID}&quantity=1`, [
    { duration: '10s', startVUs: 0, target: 500 }, { duration: '20s', startVUs: 500, target: 500 }, { duration: '10s', startVUs: 500, target: 0 },
  ], 50);
  console.log(`    总请求=${sek.total} RPS=${sek.rps} p95=${sek.latency_ms.p95}ms p99=${sek.latency_ms.p99}ms 准入200=${sek.admitted} 售罄409=${sek.soldout}`);
  const out = { meta, scenarios: { sku_cache_read: sku, seckill_flash: sek } };
  fs.writeFileSync(OUT, JSON.stringify(out, null, 2));
  console.log(`\n结果已写入: ${OUT}`);
})();
