// catalog-seckill-k6.js —— catalog-service Phase 4 性能压测（SKU 缓存命中 + 秒杀 Lua 预扣）
//
// 运行前置：
//   1) 启动依赖：MySQL（或 H2 内存兜底）、Redis(6379)、catalog-service(8082)；并执行种子
//      （deployment/demo/reset.sh 已为 DEMO-SKU-103 播种秒杀配额；本地压测用 BASE_URL/SKU_ID 指向已激活 SKU）。
//   2) 安装 k6：https://k6.io/docs/get-started/installation/
//
// 运行示例（本地）：
//   k6 run -e BASE_URL=http://localhost:8082 -e SKU_ID=1 -e SECKILL_SKU_ID=1 \
//       -o json=results/k6-result.json deployment/performance/catalog-seckill-k6.js
//
// 说明：
//   - sku_cache_read 场景：持续 GET /skus/{id}（op=sku_read），验证 cache-aside（命中 Redis 后
//     跳过 DB）在高并发下的延迟与 DB 卸载效果。
//   - seckill_flash 场景：瞬时高并发 POST /internal/stock/seckill/deduct（op=seckill_deduct），
//     验证 Lua 原子预扣的准入上限（配额耗尽后返回 409，而非击穿 DB）。下单侧另有固定窗口限流
//     （429 快速失败）。请求按 op 打 tag，便于在 JSON 结果中按端点拆分指标；
//     seckill_admitted / seckill_soldout 两个 Counter 记录 200/409 的实际计数。

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';
const SKU_ID = __ENV.SKU_ID || '103';
const SECKILL_SKU_ID = __ENV.SECKILL_SKU_ID || '103';

const seckillAdmitted = new Counter('seckill_admitted'); // 200：配额充足，已原子预扣
const seckillSoldout = new Counter('seckill_soldout');   // 409：配额耗尽，快速拒绝

export const options = {
  scenarios: {
    sku_cache_read: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m', target: 200 },
        { duration: '30s', target: 0 },
      ],
      exec: 'skuRead',
    },
    seckill_flash: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 500 },
        { duration: '20s', target: 500 },
        { duration: '10s', target: 0 },
      ],
      exec: 'seckillDeduct',
    },
  },
  // SLO（草案）：p95<50ms / p99<120ms / 错误率<1%（仅作用于缓存读场景；秒杀场景的 409 属预期行为，
  // 不计入失败率，仅对其延迟设 SLO）。
  // 注意 k6 v2.x 的 trend stat 必须写成 p(95) 形式，且阈值可按 tag 子指标作用域（{op:...}）。
  thresholds: {
    'http_req_duration{op:sku_read}': ['p(95)<50', 'p(99)<120'],
    'http_req_failed{op:sku_read}': ['rate<0.01'],
    'http_req_duration{op:seckill_deduct}': ['p(95)<50', 'p(99)<120'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function skuRead() {
  const res = http.get(`${BASE}/skus/${SKU_ID}`, { tags: { op: 'sku_read' } });
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(0.1);
}

export function seckillDeduct() {
  const url = `${BASE}/internal/stock/seckill/deduct?skuId=${SECKILL_SKU_ID}&quantity=1`;
  const res = http.post(url, null, { tags: { op: 'seckill_deduct' } });
  if (res.status === 200) {
    seckillAdmitted.add(1);
  } else if (res.status === 409) {
    seckillSoldout.add(1);
  }
  // 200 = 准入并扣减；409 = 配额已耗尽（预期行为，非错误）
  check(res, { 'admitted or sold-out(409)': (r) => r.status === 200 || r.status === 409 });
  sleep(0.05);
}
