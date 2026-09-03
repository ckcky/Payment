<a id="adr-0058"></a>


- **状态**：Accepted（**基线已建立**——3 项 Phase 4 实测达标；原 3 项 🟡 待专项验证已于 2026-09-04 落地为**真实 JUnit 并发/量化测试**（embedded Redis，in-JVM，无需 Docker），见「后续验证（R3 实施项）」）
- **日期**：2026-09-03（R3 实施态更新 2026-09-04）
- **关联**：`deployment/performance/`（压测产物与 loadgen）、`ADR-0044`（Redis 引入论证）、`order-service`/`catalog-service` 测试源码

## 基线表

| 指标 | 目标 | 实测（Phase 4，2026-09-02，MySQL + Redis 默认配置） | 状态 |
|---|---|---|---|
| 读路径 p99 延迟 | ≤ 50ms | **16.83ms** | ✅ 达标 |
| 命令路径 p99（seckill） | ≤ 1s | **434ms**（配额 1,000,000，p95 415.7ms） | ✅ 达标 |
| 读路径 DB 卸载率（Cache-Aside） | — | **99.98%** | ✅ 实测 |
| 秒杀库存预扣正确性 | 不超卖/不漏卖 | Redis Lua 原子预扣，fail-closed | ✅ JUnit 并发验证（`SeckillStockConcurrencyTest`，2026-09-04） |
| 并发幂等接管（order 入口） | 不重复下单 | `OrderEntryIdempotencyService` 409+Retry-After | ✅ JUnit 并发验证（`OrderEntryIdempotencyConcurrencyTest`，2026-09-04） |
| 限流（秒杀固定窗口） | 429 不返回 Retry-After | 单机内存固定窗口（ADR-0045，非 Redis） | ✅ JUnit 量化验证（`RateLimiterConcurrencyTest`，2026-09-04） |

## Consequences（后果）

- 读/命令两条 p99 与 DB 卸载率**已有 Phase 4 实测数据并达标**，属已验证基线；原 3 项 🟡（秒杀正确性、并发幂等、限流量化）**已于 2026-09-04 落地为真实 JUnit 并发/量化测试**（embedded Redis，in-JVM，不依赖 Docker），现标 ✅ 已验证（单元级并发）。
- ⚠️ 诚实边界：上述 ✅ 为**单元级并发/量化验证**（单 JVM 内多线程 + 独立 embedded Redis 实例），证明核心不变量（不超卖、不重复下单、窗口不超发）成立；**跨进程/分布式压测**（多节点、真实网络、k6/loadgen 大流量）仍属后续专项，需在具备 Docker 的环境经 `deployment/start-all.sh` 拉起全栈后执行 `node deployment/performance/catalog-seckill-loadgen.js`，本环境 Docker 不可用故未跑。
- 压测方法学：k6 二进制被代理拦截从未跑过；实际用 Node 负载生成器（`deployment/performance/catalog-seckill-loadgen*.js`）等价复刻，产物在 `deployment/performance/results/`。

## 后续验证（R3 实施项 · 2026-09-04 已完成单元级并发/量化）

R3「需要实现」已落地为可重复运行的真实 JUnit 测试（不依赖 Docker，in-JVM embedded Redis），从「🟡 未验证」升级为「✅ 单元级并发已验证」：

1. **秒杀库存预扣正确性** ✅ `catalog-service/.../seckill/SeckillStockConcurrencyTest`
   - `noOversellUnderConcurrency`：配额 10、50 并发各扣 1 → 断言恰好 10 个 `allowed`、40 个 `deny`，且扣减后剩余恒为 `0`（Lua 原子预扣不超卖）；另含 `rollback` 回补、未播种 `bypass` 用例。
2. **并发幂等接管（order 入口）** ✅ `order-service/.../idempotency/OrderEntryIdempotencyConcurrencyTest`
   - `concurrentDuplicateYieldsExactlyOneProceed`：同 key 50 并发 → 断言恰好 1 个 `PROCEED`、49 个 `CONFLICT`（不重复下单）；`lifecycleProceedThenCompleteThenReplay` 串联 IN_PROGRESS→DONE→REPLAY 全链路。
3. **限流（秒杀固定窗口）量化** ✅ `order-service/.../ratelimit/RateLimiterConcurrencyTest`
   - `singleWindowCapsAtCapacityUnderConcurrency`：单窗口 100 并发抢容量 10 → 断言恰好 10 个成功、90 个被拒（不超发、不漏拦）。

> 运行（单元级，无需 Docker）：`mvn -pl order-service,catalog-service -am test -Dtest='OrderEntryIdempotencyConcurrencyTest,SeckillStockConcurrencyTest,RateLimiterConcurrencyTest'`
> 运行（分布式压测，需 Docker 环境）：`bash deployment/start-all.sh`（含 Nacos）拉起全栈后，执行 `node deployment/performance/catalog-seckill-loadgen.js` 复刻 Phase 4 基线并产出新报告——本环境 Docker 不可用，列为后续专项。
