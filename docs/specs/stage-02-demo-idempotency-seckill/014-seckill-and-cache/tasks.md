# Tasks: 014-seckill-and-cache

**Feature**：`014-seckill-and-cache`
**状态**：全部完成（**收口型 tasks**，2026-09-02 补写；代码先行，任务为事后反向登记）
**关联**：`spec.md` / `plan.md`

| # | 任务 | 验收方式 | 状态 |
| --- | --- | --- | --- |
| T001 | `SkuCache` + `SkuCacheView`（cache-aside、TTL、显式 `@JsonProperty`、fail-open） | `SkuCacheTest` | ✅ |
| T002 | `CatalogCacheProperties`（enabled / ttlSeconds / keyPrefix） | `SkuCacheTest#disabledBypassesCacheAndAlwaysHitsRepo` | ✅ |
| T003 | `seckill-deduct.lua`（三返回值 `-2 bypass` / `-1 deny` / `≥0 remaining`） | `SeckillStockServiceTest` | ✅ |
| T004 | `SeckillStockService`（tryPreDeduct 三态 / seed / rollback / **fail-closed** + 降级指标） | `SeckillStockServiceTest#denyWhenRedisDownProtectsStock` | ✅ |
| T005 | `SeckillProperties` + `CatalogRedisConfig`（绑定属性 + 注册 Lua 脚本 Bean） | 编译 + 上下文加载 | ✅ |
| T006 | `SeckillStockController`（`/internal/stock/seckill/{seed,deduct,rollback}`，deny → 409） | `SeckillStockControllerTest` | ✅ |
| T007 | order 侧 `CatalogClient.trySeckillDeduct` / `rollbackSeckill` + Feign 接线 | `SuccessfulPurchaseScenarioTest` | ✅ |
| T008 | 下单逐行秒杀准入 + 失败回滚（`releaseStock` + `rollbackSeckill`） | `SuccessfulPurchaseScenarioTest` | ✅ |
| T009 | `RateLimiter` 固定窗口（synchronized、按 bucket 计数） | `RateLimiterTest` | ✅ |
| T010 | `RateLimitProperties` + `RateLimitInterceptor` + `RateLimitConfig`（仅 `/orders`、429、无 Retry-After、可关闭） | `RateLimiterTest` + 配置评审 | ✅ |
| T011 | 超时释放**补齐** `rollbackSeckill`（与 `releaseStockForOrder` 对齐） | `OrderTimeoutSchedulerTest` 新增 `verify(rollbackSeckill)` | ✅ |
| T012 | k6 压测脚本 `performance/catalog-seckill-k6.js` + README | 脚本已建；**未实跑** | ⚠️ |

## 说明与遗留

- T001~T011 为**代码先行落地后**反向登记，属 ADR-0053 的收口动作。
- **T012 未完成**：roadmap §7 的验收口径是「k6：库存 100 / 并发 5000 ⇒ 不超卖、不漏卖、无重复单、限流生效」，
  脚本已建但**本环境无 Docker / MySQL / Redis，从未实跑**，因此该断言**无证据**（见 `acceptance.md` §3）。
- **测试覆盖缺口（如实记录）**：`RateLimitInterceptor` 的**拦截器层**无 MockMvc 测试
  （只测了 `RateLimiter` 本身），即「429 响应体与不返回 Retry-After」未被直接断言。补测试列为 TODO。
