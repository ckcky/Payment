# Plan: 013-inventory-reservation

**Feature**：`013-inventory-reservation`
**状态**：已实现（**收口型 plan**，2026-09-02 补写，代码先行）
**关联**：`spec.md` ｜ ADR-0041~0043、ADR-0045、ADR-0053（`docs/adr/0014-next-stage-decisions.md`、`0015-wip-ahead-of-roadmap.md`）

> 本 plan 记录**已落地实现的既有设计**，不是前瞻方案。凡与 `next-stage-design.md` §6 草案不一致处，一律以代码为准并在 spec 中标注差异。

## 1. 技术方案

### 1.1 领域层（catalog-service）

- `Stock` 聚合：SKU 粒度，`total / available / reserved / sold`；每次迁移后 `assertInvariant()` 强制 `total = available + reserved + sold` 且三项非负；`rehydrate(...)` 供持久化重建。
- `StockReservation`：`reservation_id` 即幂等键，状态机 `PENDING → CONFIRMED | RELEASED`，`deduct_id` 记录支付单号。

### 1.2 应用层

- `StockApplicationService`：`seedStock / reserve / confirm / release`，幂等规则见 spec FR-008~FR-010。
- 幂等键统一为 `order:{orderId}:sku:{skuId}`，**三处构造必须一致**（下单 / 超时调度 / 支付成功回调）。

### 1.3 持久化

- `stock`（含 `version` 乐观锁）+ `stock_reservation`（PK = `reservation_id`），DDL 见 `deployment/schema/02-catalog-schema.sql`。
- 仓储 `MybatisStockRepository` / `MybatisStockReservationRepository`（MyBatis-Plus）。
- **并发控制：MyBatis-Plus 乐观锁**（`BaseEntity.@Version` + `OptimisticLockerInnerInterceptor`），`updateById` 影响行数 0 ⇒ 抛 `CONFLICT`。
  > 与草案差异：未采用 `UPDATE ... WHERE available >= ?` 条件更新，理由与取舍见 `spec.md` §2.3 注。

### 1.4 编排（order-service）

- 下单：逐行 `reserveStock` + 秒杀预扣（014），失败逐行回滚 + `order.cancel()`。
- 支付成功：`onPaymentSucceeded` → `markPaid` 幂等闸门 → `confirmStock(deductId = paymentId)`。
- 超时：`OrderTimeoutScheduler`（Redis ZSet 时间轮）→ `releaseStock` + `rollbackSeckill` + 取消订单 + `finally ZREM`。
- 领域层只依赖 `CatalogClient` 端口，生产实现为 `CatalogFeignClient`（Feign，默认 `http://localhost:8082`）。

## 2. 依赖

- MySQL 8（`catalog` 库，需含 `stock` / `stock_reservation` 表）。
- Redis（超时 ZSet，与 014 缓存 / 秒杀共用同一实例）。
- catalog-service → order-service 同步 RPC 可达。

## 3. 测试策略

| 层 | 测试 | 覆盖 |
| --- | --- | --- |
| 领域 | `StockAggregateTest` | 不变量守卫、三段式迁移、非法数量、超量 confirm/release |
| 应用 | `StockApplicationServiceTest` | seed / reserve / confirm / release 及各自幂等分支 |
| 调度 | `OrderTimeoutSchedulerTest` | ZSet 登记 score、disabled 跳过、到期取消 + 释放 + **回补秒杀配额** + ZREM |
| 场景 | `SuccessfulPurchaseScenarioTest` | 下单→支付成功端到端、SKU 不可售、库存不足 409 |

- 并发正确性（不超卖）由乐观锁保证，**未做全栈并发压测**（见 014 L2）。

## 4. 风险

- Redis 不可用 ⇒ 超时取消降级，悬挂预占需人工 / 对账兜底（spec L1）。
- 多实例重复扫描未去重（spec L2）。
- **缺第 3 道防线**：`sold + reserved` 与订单 / 支付事实的定期核对未实现（spec L3）。
- 与 011/012 在 `order-service` 纠缠，无法拆成阶段纯提交（spec L4 / ADR-0053）。
