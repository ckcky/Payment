<a id="adr-0058"></a>


- **状态**：Accepted（**基线已建立**——3 项有 Phase 4 实测达标，3 项待专项并发/量化验证，见「后续验证（R3 实施项）」）
- **日期**：2026-09-03（R3 实施态更新 2026-09-04）
- **关联**：`deployment/performance/`（压测产物与 loadgen）、`ADR-0044`（Redis 引入论证）

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

- 读/命令两条 p99 与 DB 卸载率**已有 Phase 4 实测数据并达标**，属已验证基线；其余 3 项（秒杀正确性、并发幂等、限流量化）**如实标 🟡 待专项验证**，禁止写成已达标。
- 压测方法学：k6 二进制被代理拦截从未跑过；实际用 Node 负载生成器（`deployment/performance/catalog-seckill-loadgen*.js`）等价复刻，产物在 `deployment/performance/results/`。

## 后续验证（R3 实施项）

基线已建立，但以下项仍需专项测试从「🟡 未验证」升级为「✅ 已验证」，构成 ADR-0058 的完整落地：

1. **秒杀库存预扣正确性**：破坏性并发压测（远超库存的并发扣减），断言不超卖/不漏卖；覆盖 Redis Lua 原子预扣 fail-closed 路径。
2. **并发幂等接管（order 入口）**：并发重复提交同一 `payment:{orderId}`，断言仅 1 次成功、其余 409 + `Retry-After`；建议用嵌入式/Testcontainers Redis 做 JUnit 集成测试。
3. **限流（秒杀固定窗口）量化**：在 `deployment/performance/` 增加固定窗口限流场景，断言 429 不含 `Retry-After`、且限流阈值与预期一致。

> 运行方式：`bash deployment/start-all.sh`（现已含 Nacos）拉起全栈后，执行 `node deployment/performance/catalog-seckill-loadgen.js` 复刻 Phase 4 基线并产出新报告。
