<a id="adr-0058"></a>


- **状态**：Accepted（**标注「未验证目标」**——以下目标多为建议值，仅 2 项有 Phase 4 实测）
- **日期**：2026-09-03
- **关联**：`deployment/performance/`（压测产物）、`ADR-0044`（Redis 引入论证）

## 基线表

| 指标 | 目标 | 实测（Phase 4，2026-09-02，MySQL + Redis 默认配置） | 状态 |
|---|---|---|---|
| 读路径 p99 延迟 | ≤ 50ms | **16.83ms** | ✅ 达标 |
| 命令路径 p99（seckill） | ≤ 1s | **434ms**（配额 1,000,000，p95 415.7ms） | ✅ 达标 |
| 读路径 DB 卸载率（Cache-Aside） | — | **99.98%** | ✅ 实测 |
| 秒杀库存预扣正确性 | 不超卖/不漏卖 | Redis Lua 原子预扣，fail-closed | 🟡 单测覆盖，未做破坏性并发压测 |
| 并发幂等接管（order 入口） | 不重复下单 | `OrderEntryIdempotencyService` 409+Retry-After | 🟡 未做真并发压测 |
| 限流（秒杀固定窗口） | 429 不返回 Retry-After | 单机内存固定窗口（ADR-0045，非 Redis） | 🟡 未量化验证 |

## Consequences（后果）

- 仅有读/命令两条 p99 与 DB 卸载率有数据，其余**如实标「未验证」**，禁止写成已达标。
- 压测方法学：k6 二进制被代理拦截从未跑过；实际用 Node 负载生成器（`deployment/performance/catalog-seckill-loadgen*.js`）等价复刻。
- 后续：补齐真并发压测与限流量化前，不得将「未验证」项升级为「已验证目标」。
