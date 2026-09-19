<a id="adr-0058"></a>


- **状态**：Accepted（**基线已建立并含分布式压测**——3 项 Phase 4 实测达标；3 项 🟡 先于 2026-09-04 落地为真实 JUnit 并发/量化测试，同日又在 Docker 全栈环境完成**分布式端到端验证**（真网络、多连接并发、500 VU），见「后续验证（R3 实施项）」与 `deployment/performance/README.md` §4）
- **日期**：2026-09-03（R3 实施态更新 2026-09-04；分布式验证回填 2026-09-04）
- **关联**：`deployment/performance/`（压测产物与 loadgen）、`ADR-0044`（Redis 引入论证）、`order-service`/`catalog-service` 测试源码、`deployment/performance/R3-distributed-verification.md`（执行清单）

## 基线表

| 指标 | 目标 | 实测（Phase 4，2026-09-02，MySQL + Redis 默认配置） | 状态 |
|---|---|---|---|
| 读路径 p99 延迟 | ≤ 50ms | **16.83ms** | ✅ 达标 |
| 命令路径 p99（seckill） | ≤ 1s | **434ms**（配额 1,000,000，p95 415.7ms） | ✅ 达标 |
| 读路径 DB 卸载率（Cache-Aside） | — | **99.98%** | ✅ 实测 |
| 秒杀库存预扣正确性 | 不超卖/不漏卖 | Redis Lua 原子预扣，fail-closed | ✅ JUnit 并发验证 + **分布式验证（500 VU 抢配额 10：38,801 请求 → 恰好 10×200、38,791×409、0×500，Redis 终值 0，2026-09-04）** |
| 并发幂等接管（order 入口） | 不重复下单 | `OrderEntryIdempotencyService` 409+Retry-After | ✅ JUnit 并发验证 + **分布式验证（同 key 50 并发：1×201 + 49×409 带 Retry-After，重放 200 同单号，2026-09-04）** |
| 限流（秒杀固定窗口） | 429 不返回 Retry-After | 单机内存固定窗口（ADR-0045，非 Redis） | ✅ JUnit 量化验证 + **分布式验证（100 并发单窗口：恰好 50 放行 + 50×429 全部无 Retry-After，2026-09-04）** |

## Consequences（后果）

- 读/命令两条 p99 与 DB 卸载率**已有 Phase 4 实测数据并达标**，属已验证基线；原 3 项 🟡（秒杀正确性、并发幂等、限流量化）已于 2026-09-04 落地为真实 JUnit 并发/量化测试，并**同日在 Docker 全栈环境完成分布式端到端验证**（见下），三项不变量在跨进程/真网络场景同样成立。
- ⚠️ 诚实边界（2026-09-04 更新）：分布式验证为**单机全栈**（infra 容器 + 宿主进程微服务，非多节点部署），网络为回环；秒杀 p95 在 500 VU 下为 **473ms**，系 Lettuce 单共享连接串行排队的排队延迟（Redis 单线程 + 连接复用），大配额对照跑（p95 528ms、RPS 976、0 超卖）结论一致——**正确性不变量不受影响，吞吐上限属连接模型调优专项**（连接池/Lettuce 多连接），未纳入本期。
- 环境备注：分布式实跑中 JDBC `localhost:3306` 解析到宿主原生 MySQL 8.0.46（WorkBuddy local-mysql 先于 Docker 占用 127.0.0.1:3306，容器 MySQL 被架空），与 Phase 4 基线同源故数据可比；JVM 统一 `-Xmx384m`（start-all.sh 默认）。
- 压测方法学：k6 二进制被代理拦截从未跑过；实际用 Node 负载生成器（`catalog-seckill-loadgen.js` / `order-idempotency-verify.js`）等价复刻，产物在 `deployment/performance/results/`。

## 后续验证（R3 实施项 · 2026-09-04 已完成单元级并发/量化）

R3「需要实现」已落地为可重复运行的真实 JUnit 测试（不依赖 Docker，in-JVM embedded Redis），从「🟡 未验证」升级为「✅ 单元级并发已验证」：

1. **秒杀库存预扣正确性** ✅ `catalog-service/.../seckill/SeckillStockConcurrencyTest`
   - `noOversellUnderConcurrency`：配额 10、50 并发各扣 1 → 断言恰好 10 个 `allowed`、40 个 `deny`，且扣减后剩余恒为 `0`（Lua 原子预扣不超卖）；另含 `rollback` 回补、未播种 `bypass` 用例。
2. **并发幂等接管（order 入口）** ✅ `order-service/.../idempotency/OrderEntryIdempotencyConcurrencyTest`
   - `concurrentDuplicateYieldsExactlyOneProceed`：同 key 50 并发 → 断言恰好 1 个 `PROCEED`、49 个 `CONFLICT`（不重复下单）；`lifecycleProceedThenCompleteThenReplay` 串联 IN_PROGRESS→DONE→REPLAY 全链路。
3. **限流（秒杀固定窗口）量化** ✅ `order-service/.../ratelimit/RateLimiterConcurrencyTest`
   - `singleWindowCapsAtCapacityUnderConcurrency`：单窗口 100 并发抢容量 10 → 断言恰好 10 个成功、90 个被拒（不超发、不漏拦）。

> 运行（单元级，无需 Docker）：`mvn -pl order-service,catalog-service -am test -Dtest='OrderEntryIdempotencyConcurrencyTest,SeckillStockConcurrencyTest,RateLimiterConcurrencyTest'`

## 分布式端到端验证（2026-09-04 已实跑，Docker 全栈）

在 Docker 基础设施（Nacos/Redis/Prometheus/Grafana/Loki+Promtail 容器）+ 宿主进程微服务的单机全栈上，
三项不变量以真网络、多连接并发复验（详细数值见 `deployment/performance/README.md` §4）：

1. **秒杀 Lua 原子预扣** ✅（破坏性场景：SKU 配额 10，500 VU）
   - 38,801 次 `POST /internal/stock/seckill/deduct` → 恰好 **10×200、38,791×409、0×500**；
     Redis `seckill:sku:{id}` 终值 **0**（不超卖）。大配额（1,000,000）对照跑：p95 528ms / p99 564ms、
     RPS 976、全程 200 准入、0 超卖。
2. **下单入口幂等** ✅（`order-idempotency-verify.js`，新增分布式验证脚本）
   - 相同 `Idempotency-Key` 50 并发 `POST /orders` → 恰好 1×201、49×409（全部 `Retry-After: 1`）；
     窗口翻转后同 key 重放 → 200 且订单号与首次一致。
3. **限流量化** ✅（100 并发 448ms 进入同一窗口）
   - 恰好 50 放行（下游库存 409）+ 50×429；429 全部**无** `Retry-After`（`retryable:false`），
     与「拒绝不允许重试」决策一致。

> 复现步骤：`deployment/performance/R3-distributed-verification.md`（已更新为「已执行」并附实测数值）。
