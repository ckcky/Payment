/*
 * audit-loadtest.js —— spec 017 审计端点压测（k6）
 *
 * 目标：审计只读端点（试算平衡 / SUSPENSE 余额 / 差异查询 / 跨账事实 / 账本分录 / 结算门禁）
 * 的延迟（P95/P99）与吞吐（QPS）、错误率。
 *
 * 前置：
 *   1) 全量服务已启动（reconciliation 8088 / settlement 8089 / ledger 8090）；
 *   2) scenario-audit.sh 已跑过（存在可回查的审计批次；脚本自动创建 LOAD 批次兜底）。
 *
 * 用法：
 *   k6 run -e BASE_RECON=http://localhost:8088 deployment/performance/audit-loadtest.js
 *   k6 run -e VUS=30 -e DURATION=90s deployment/performance/audit-loadtest.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { TrendRate, Counter } from 'k6/metrics';

const BASE_RECON = __ENV.BASE_RECON || 'http://localhost:8088';
const BASE_SETTLE = __ENV.BASE_SETTLE || 'http://localhost:8089';
const BASE_LEDGER = __ENV.BASE_LEDGER || 'http://localhost:8090';
const PERIOD = __ENV.AUDIT_PERIOD || '2026-08-31';
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '60s';

export const options = {
  scenarios: {
    audit_read: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    // Google SRE 黄金信号：延迟（P95/P99）与错误率
    'http_req_duration{endpoint:trial-balance}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{endpoint:fast-reads}': ['p(95)<300', 'p(99)<800'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const params = { headers: { 'Content-Type': 'application/json' } };

// 初始化：创建/回查压测专用审计批（幂等），拿 batchNo 供差异端点读取
let batchNo = '';
export function setup() {
  const create = http.post(`${BASE_RECON}/internal/audit/batches`,
    JSON.stringify({ period: PERIOD, scope: 'CERTIFICATE', triggeredBy: 'k6-loadtest' }), params);
  check(create, { 'setup: batch created/reused': (r) => r.status === 201 || r.status === 200 });
  if (create.status === 201 || create.status === 200) {
    batchNo = create.json('batchNo') || '';
  }
  console.log(`setup: batchNo=${batchNo || '(none)'}`);
}

export default function () {
  const dice = Math.random();

  if (dice < 0.30) {
    // 试算平衡：聚合全账本（最重的只读端点）
    const r = http.get(`${BASE_RECON}/internal/audit/trial-balance`,
      { ...params, tags: { endpoint: 'trial-balance' } });
    check(r, { 'trial-balance 200': (r) => r.status === 200 });
  } else if (dice < 0.55 && batchNo) {
    // 差异清单
    const r = http.get(`${BASE_RECON}/internal/audit/batches/${batchNo}/differences`,
      { ...params, tags: { endpoint: 'fast-reads' } });
    check(r, { 'differences 200': (r) => r.status === 200 });
  } else if (dice < 0.70) {
    // SUSPENSE 余额
    const r = http.get(`${BASE_RECON}/internal/audit/suspense-balance`,
      { ...params, tags: { endpoint: 'fast-reads' } });
    check(r, { 'suspense 200': (r) => r.status === 200 });
  } else if (dice < 0.82) {
    // 跨账事实（settlement audit-facts）
    const r = http.get(`${BASE_SETTLE}/internal/settlements/audit-facts?period=${PERIOD}`,
      { ...params, tags: { endpoint: 'fast-reads' } });
    check(r, { 'audit-facts 200': (r) => r.status === 200 });
  } else if (dice < 0.92) {
    // 账本分录全量（只读，上限 1000）
    const r = http.get(`${BASE_LEDGER}/internal/ledger/postings/all`,
      { ...params, tags: { endpoint: 'trial-balance' } });
    check(r, { 'postings 200': (r) => r.status === 200 });
  } else {
    // 结算门禁（reconciliation 内部聚合）
    const r = http.get(`${BASE_RECON}/internal/audit/settlement-gate?period=${PERIOD}`,
      { ...params, tags: { endpoint: 'trial-balance' } });
    check(r, { 'settlement-gate 200': (r) => r.status === 200 });
  }

  sleep(0.2);
}
