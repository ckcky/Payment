# R3 分布式压测执行清单（Docker 环境）

> 状态：**已执行（2026-09-04）**，实测数值见下「执行结果」与 `deployment/performance/README.md` §4，
> ADR-0058 已同步回填。
> 用途：把 ADR-0058 中 3 项「✅ 单元级并发/量化验证」升级为「✅ 已含分布式压测」，复刻 Phase 4 基线并产出新报告。
> 单元级并发不变量已由 `SeckillStockConcurrencyTest` / `OrderEntryIdempotencyConcurrencyTest` / `RateLimiterConcurrencyTest` 覆盖（见 ADR-0058）。
>
> 关联：ADR-0058、ADR-0044（Redis 引入论证）、`deployment/start-all.sh`（已含 Nacos）。

## 前置依赖
- Docker + Docker Compose（起全栈）
- Node.js（跑 `catalog-seckill-loadgen.js` / `generate-report.js`，零外部依赖）
- k6 二进制**可选**（本环境代理拦截不可用；用 Node loadgen 替代）

## 执行步骤（一条命令一行的清单）

```bash
# 0) 起全栈：Nacos :8848、MySQL、Redis、catalog-service :8082、order-service 等
bash deployment/start-all.sh
#    就绪校验（脚本内含 Nacos 就绪等待；另可手动）：
curl -fsS "http://127.0.0.1:8848/nacos/v1/ns/operator/metrics"   # {"status":"UP"} 即就绪

# 1) 播种秒杀配额（SKU 103 播种 10）
bash deployment/demo/reset.sh
#    备用（无需 httpie）：
curl -X POST "http://localhost:8082/internal/stock/seckill/seed?skuId=103&total=10"

# 2) 跑压测（Node loadgen，复刻 k6 的 sku_cache_read + seckill_flash 两套场景）
BASE_URL=http://localhost:8082 SKU_ID=103 SECKILL_SKU_ID=103 \
  OUT=deployment/performance/results/r3-load-result.json \
  node deployment/performance/catalog-seckill-loadgen.js

# 3) 生成自包含 HTML 报告（内联 SVG，无外部依赖）
RESULT=deployment/performance/results/r3-load-result.json \
  OUT=deployment/performance/results/r3-perf-report.html \
  node deployment/performance/generate-report.js
```

> k6 原生命令（仅当 k6 可用时）：
> `k6 run -e BASE_URL=http://localhost:8082 -e SKU_ID=103 -e SECKILL_SKU_ID=103 deployment/performance/catalog-seckill-k6.js`

## 验证不变量（与单元测试对应，真网络 + 多连接并发）

| 项（ADR-0058） | 分布式压测断言 | 对应单元测试 |
|---|---|---|
| ① 秒杀 Lua 原子预扣 | `seckill_flash`：500 VU 抢配额 10 → 恰好 10×200 准入、其余 409、绝无 500；Redis `seckill:sku:103` 终值 == 0（不超卖）；未播种 SKU 走 `bypass`(200) | `SeckillStockConcurrencyTest.noOversellUnderConcurrency`（50 并发/配额10，10 allowed/40 deny） |
| ② 下单并发幂等 | order-service 起好后，相同 `Idempotency-Key` 并发 `POST /orders` → 1×200 + 其余 409 + `Retry-After`（不重复下单） | `OrderEntryIdempotencyConcurrencyTest.concurrentDuplicateYieldsExactlyOneProceed`（50 并发，1 PROCEED/49 CONFLICT） |
| ③ 限流量化 | 高流量下 order 固定窗口返回 429 快速失败、**不含** `Retry-After`；阈值与预期一致 | `RateLimiterConcurrencyTest.singleWindowCapsAtCapacityUnderConcurrency`（100 并发抢容量10，恰好10成功/90拒） |

> 注：当前 `catalog-seckill-loadgen.js` 侧重 catalog（缓存/秒杀）。② 的 order 入口并发幂等建议在 order-service 起好后补一个针对性压测场景，或复用单元结论并如实记录；③ 的限流为 order 侧固定窗口，单元已覆盖，分布式可只做阈值确认。

## 回填 ADR-0058
1. 将目录「① 秒杀 / ② 幂等 / ③ 限流」三行的 ✅ 备注由「单元级并发/量化验证（2026-09-04）」改为「已含分布式压测（<实跑日期>）」。
2. 将实跑数值填入 `deployment/performance/README.md` §4 表格（p95/p99、200/409 计数、错误率、DB 查询计数）。
3. 报告产物：本清单生成的 `*-perf-report.html` 与 `*-load-result.json` 归档至 `deployment/performance/results/`。

> 以上三步已于 2026-09-04 完成。

## 执行结果（2026-09-04 实跑）

| 项（ADR-0058） | 实测 |
|---|---|
| ① 秒杀 Lua 原子预扣 | 配额 10、500 VU：38,801 次扣减 → **10×200 / 38,791×409 / 0×500**，Redis `seckill:sku:{id}` 终值 **0**（不超卖）；大配额 1,000,000 对照：p95 528ms、RPS 976、0 超卖 |
| ② 下单并发幂等 | 同 key 50 并发 `POST /orders` → **1×201 + 49×409**（全部 `Retry-After: 1`）；窗口翻转后同 key 重放 → 200 且订单号一致 |
| ③ 限流量化 | 100 并发 448ms 单窗口 → 恰好 50 放行（下游库存 409）+ **50×429 全部无 Retry-After**（`retryable:false`），capacity=50/1s 确认 |

补充产物：`order-idempotency-verify.js`（② 的分布式验证脚本，`SKU_ID/MERCHANT_ID/CONCURRENCY` 环境变量可配）；
`results/r3-load-result.json`、`results/r3-perf-report.html`、`results/r3-idempotency-result.json`。

环境备注（如实记录）：宿主原生 MySQL 8.0.46（WorkBuddy local-mysql）先于 Docker 占用 `127.0.0.1:3306`，
服务 JDBC 实际连它而非容器 MySQL（数据同源 Phase 4 基线，可比）；JVM 统一 `-Xmx384m`。

## 已知文档漂移（已修）
- `deployment/performance/README.md` 原写 `bash demo/reset.sh`，脚本实际位于 `deployment/demo/reset.sh`；现已更正并补充 Node loadgen 作为 k6 的免代理替代。
