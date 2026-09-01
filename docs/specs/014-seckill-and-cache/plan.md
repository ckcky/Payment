# Plan: 014-seckill-and-cache

**Feature**：`014-seckill-and-cache`
**状态**：已实现（**收口型 plan**，2026-09-02 补写；代码先行）
**关联**：`spec.md` ｜ ADR-0043~0046、ADR-0053（`docs/adr/0014-next-stage-decisions.md`、`0015-wip-ahead-of-roadmap.md`）

> 本 plan 记录**已落地实现的既有设计**。与 `next-stage-design.md` §7 草案的最大差异：
> **§7 要求「压测基线 → 论证引入」，实现的 Redis 未经该闸门直接引入**（ADR-0044 / ADR-0053）。

## 1. 技术方案

### 1.1 SKU 读缓存（catalog）

- `SkuCache` cache-aside：`getById` / `getByCode` 命中 Redis 直接返回，未命中回源 `SkuRepository` 并回写（TTL 300s）。
- 序列化走 `SkuCacheView`（显式 `@JsonProperty`，规避未开启 `-parameters` 的反序列化风险）。
- 写路径（创建 / 激活 / 暂停）调 `evict(id, code)` 失效。
- **fail-open**：读 / 写 / 失效三处 Redis 异常均记 WARN 并回退，不阻断请求。

### 1.2 秒杀配额预扣（catalog）

- `SeckillStockService.tryPreDeduct` 执行 `seckill-deduct.lua`：
  `EXISTS == 0 → -2（bypass）`；`cur < qty → -1（deny）`；否则 `DECRBY` 返回新值（`allow`）。
- Redis 异常 → **`deny`（fail-closed）** + 指标 `catalog_seckill_degraded_total{reason="redis_unavailable"}`。
- `seed` / `rollback` 为演示播种与失败回补，`rollback` 走 `INCRBY`。
- 端点 `SeckillStockController`：`/internal/stock/seckill/{seed,deduct,rollback}`，deny 时 409。

### 1.3 限流（order）

- `RateLimiter`：单机内存固定窗口（`synchronized` + `HashMap<bucket, Window>`），按 `bucket` 计数。
- `RateLimitInterceptor` 经 `RateLimitConfig` 注册到 `POST /orders`，超限 429 + `{"error":"rate_limit_exceeded","retryable":false}`，**不返回 `Retry-After`**。
- 可通过 `order.ratelimit.enabled=false` 关闭。

### 1.4 编排

- 下单逐行：秒杀预扣（014）→ DB 预占（013）→ 登记超时 → 创建支付意图；失败逐行回滚（释放 DB + 回补配额）+ 取消订单。
- 超时：`OrderTimeoutScheduler` 扫描 ZSet → `releaseStock` + **`rollbackSeckill`** + `order.cancel()` + `finally ZREM`。

## 2. 依赖

- 单机 Redis（`spring.data.redis`，localhost:6379，超时 1s），与 013 超时时间轮、012 入口幂等共用。
- MySQL 8（`catalog` / `order` 库）。
- 依赖 013 的 `Stock` 三段式库存作为**最终扣减真相源**。

## 3. 测试策略

| 测试 | 覆盖 |
| --- | --- |
| `SeckillStockServiceTest` | `bypassWhenSkuNotSeeded` / `denyWhenInsufficient` / `allowedWhenSufficient` / `denyWhenRedisDownProtectsStock` |
| `SeckillStockControllerTest` | 200 带 remaining / 409 配额不足 / seed+rollback 接线 |
| `SkuCacheTest` | 未命中回源回写 / 二次命中不回源 / evict 后重载 / 关闭时直连仓储 |
| `RateLimiterTest` | 窗口内限流 / bucket 独立 / 窗口过期重置 |
| `OrderTimeoutSchedulerTest` | 到期取消 + 释放 + **回补配额** + ZREM（013/014 交叉） |
| `performance/catalog-seckill-k6.js` | 压测脚本（**从未实跑**） |

## 4. 风险

- **Redis 引入偏离 §7 论证闸门**（ADR-0044 / ADR-0053），"值不值得引入"无实测证据。
- 限流为单机窗口，分布式下非全局（ADR-0045 / ADR-0046）。
- 缓存无防击穿；缓存快照含价格，未来新增改价接口必须同步 `evict`（spec L6）。
- 任何新增失败分支若漏掉 `rollbackSeckill` 即造成配额泄漏（已修一处，见 013 D1 / 本 spec FR-017）。
