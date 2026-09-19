# Acceptance: 014-seckill-and-cache

**Feature**：`014-seckill-and-cache`
**验收日期**：2026-09-02
**验收结论**：**通过（单元/组件层）｜ 压测与端到端未验证（环境受限 + 论证闸门未走）**
**关联**：`spec.md` / `plan.md` / `tasks.md`

---

## 1. 验收方式

| 层 | 方式 | 环境要求 |
| --- | --- | --- |
| 单元 / 组件 | `mvn -o clean verify -fae`（Mockito + MockMvc 局部） | 无外部依赖 |
| k6 压测（roadmap §7 的正式口径） | `k6 run performance/catalog-seckill-k6.js` | MySQL + Redis + catalog-service + k6 —— **本机不可用** |

## 2. 已验证项（✅ 有证据）

| SC | 内容 | 结果 | 证据 |
| --- | --- | --- | --- |
| SC-001 | 缓存未命中回源回写、二次命中不查 DB、Redis 异常回源不抛异常 | ✅ | `SkuCacheTest#cacheMissLoadsFromRepoAndWritesBack` / `#secondReadHitsCacheWithoutReloadingRepo` |
| SC-002 | `evict` 后强制重载；`enabled=false` 时完全绕过缓存 | ✅ | `SkuCacheTest#evictForcesReloadFromRepo` / `#disabledBypassesCacheAndAlwaysHitsRepo` |
| SC-003 | 秒杀三态：`bypass`（未播种）/ `deny`（不足）/ `allow`（充足） | ✅ | `SeckillStockServiceTest` 同名三测试 |
| SC-004 | fail-closed：Redis 异常 → `deny` + `catalog_seckill_degraded_total` | ✅ | `SeckillStockServiceTest#denyWhenRedisDownProtectsStock` |
| SC-005 | 端点 `/deduct` 在 deny 时返回 **409**；`/seed`、`/rollback` 接线正常 | ✅ | `SeckillStockControllerTest` |
| SC-006 | 限流窗口内前 N 次放行、第 N+1 次拒绝；bucket 独立；窗口过期重置 | ✅（`RateLimiter` 层） | `RateLimiterTest` |
| SC-007 | 下单失败与订单超时**两条路径**都会回补秒杀配额 | ✅（超时路径 2026-09-02 补齐并加断言） | `OrderTimeoutSchedulerTest` |
| SC-008 | `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁） | ✅ | 见 §5 |

## 3. 未验证项（❌ 无证据，不宣称通过）

| 项 | 阻塞原因 |
| --- | --- |
| **k6 压测：库存 100 / 并发 5000 ⇒ 不超卖、不漏卖、无重复单、限流生效** | 本机无 Docker / MySQL / Redis，脚本 `performance/catalog-seckill-k6.js` **从未实跑** |
| **Redis 引入论证（roadmap §7 闸门）** | 该闸门要求「先有压测基线证据，再引入 Redis」；实现顺序相反，证据至今缺失（ADR-0044） |
| 限流端到端（真实 429 响应体、无 `Retry-After` 头） | 拦截器层无 MockMvc 测试；且服务无法启动 |
| 缓存真实命中率提升 / DB 卸载效果 | 需运行时观测，同上 |

## 4. 本轮补文档过程中发现的状态

| # | 事项 | 处置 |
| --- | --- | --- |
| D1 | 超时取消未回补秒杀配额（与 013 同一缺陷，跨 Feature） | **已修复**并加断言（013 T011 / 014 T011） |
| D2 | 文档/草案宣称的验收口径（k6 压测）与现状不符 | **已如实标注**为"未验证"，不伪装通过 |
| D3 | Redis 未按 §7 闸门论证即引入 | 保留现状（ADR-0044 Accepted + ADR-0053 偏离记录），补证据列为 TODO |

## 5. 构建门禁

```bash
'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o clean verify -fae
```

**结论**：BUILD SUCCESS（16 个 reactor 条目，`architecture-tests` 边界门禁通过）。

## 6. 遗留 TODO（需决策，不阻塞本次收口）

1. **补 Redis 引入论证**：在有环境的机器上跑 k6 基线（引入前/后对比），产证据并回填 ADR-0044。
2. **补 `RateLimitInterceptor` 的 MockMvc 测试**（断言 429 body 与无 `Retry-After`）。
3. 分布式场景下限流改 Redis 令牌桶 / Lua（当前单机窗口，spec L3）。
4. 缓存防击穿（single-flight）与改价接口的 `evict` 约束（spec L5 / L6）。
