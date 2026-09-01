# Tasks: 013-inventory-reservation

**Feature**：`013-inventory-reservation`
**状态**：全部完成（**收口型 tasks**，2026-09-02 补写；代码先行，任务为事后反向登记）
**关联**：`spec.md` / `plan.md`

| # | 任务 | 验收方式 | 状态 |
| --- | --- | --- | --- |
| T001 | `Stock` 聚合：四字段 + `assertInvariant()` 不变量守卫 + `rehydrate()` 重建 | `StockAggregateTest` | ✅ |
| T002 | `StockReservation` + `ReservationStatus` 状态机 + `deductId` 记录 | `StockApplicationServiceTest` | ✅ |
| T003 | `StockRepository` / `StockReservationRepository` 端口 + MyBatis 实现（乐观锁） | `CatalogPersistenceTest` | ✅ |
| T004 | `StockApplicationService` 三段式 `reserve / confirm / release` + 三类幂等分支 | `StockApplicationServiceTest` | ✅ |
| T005 | `stock` / `stock_reservation` 表 DDL | `deployment/schema/02-catalog-schema.sql` | ✅ |
| T006 | `CatalogClient` 端口 + `CatalogFeignClient` 实现（reserve/confirm/release） | 编译 + `SuccessfulPurchaseScenarioTest` | ✅ |
| T007 | `OrderApplicationService` 下单预占 + 逐行回滚 + `order.cancel()` | `SuccessfulPurchaseScenarioTest` | ✅ |
| T008 | 支付成功确认扣减（`onPaymentSucceeded`，`deductId = paymentId`，`markPaid` 幂等闸门） | `SuccessfulPurchaseScenarioTest` | ✅ |
| T009 | `OrderTimeoutScheduler` Redis ZSet 时间轮（登记 / 扫描 / 释放 / ZREM） | `OrderTimeoutSchedulerTest` | ✅ |
| T010 | `OrderTimeoutProperties` 配置（enabled / ttl / poll / zsetKey） | `OrderTimeoutSchedulerTest` | ✅ |
| T011 | 超时释放**补齐秒杀配额回补** `rollbackSeckill`（2026-09-02 修复） | `OrderTimeoutSchedulerTest#processExpiredCancels...` 新增 `verify(rollbackSeckill)` | ✅ |
| T012 | 清理 `OrderApplicationService` 中未使用的 `stableKey` 死变量并注明库存键契约 | 编译 + 全量测试 | ✅ |

## 说明与遗留

- T001~T010 均在**代码先行落地后**反向登记，属 ADR-0053 的收口动作。
- **T011 是补写文档过程中发现的真实缺陷**：超时取消只释放 DB 库存、未回补 Redis 秒杀配额，
  与 `releaseStockForOrder` 行为不一致，会导致秒杀品超时后**配额永久泄漏（少卖）**。已修复并加断言。
- **测试覆盖缺口（如实记录，未弥补）**：`StockController`（`/internal/stock/*`）**无独立单元测试**，
  其逻辑经 `StockApplicationServiceTest` 与 `SuccessfulPurchaseScenarioTest` 间接覆盖。补测试列为 TODO。
- 全栈并发正确性（不超卖）依赖乐观锁，未在真实并发下压测（见 014 L2）。
