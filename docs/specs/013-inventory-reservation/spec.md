# Spec: 013-inventory-reservation（库存域与预占模型）

**版本**：0.2（收口型）
**日期**：2026-09-02
**状态**：Accepted（实现已存在，本 Spec 为**事后补写的收口产物**）
**输入**：`docs/architecture/next-stage-design.md` §6（F3）

> ⚠️ **收口说明（ADR-0053）**：本 Feature 的源码在 Spec 之前已写入 working tree——超前 roadmap 顺序
> （应为 011 → 012 → 013 → 014），且落地时缺 spec/plan/tasks/acceptance 与 ADR-0041~0043。
> 该偏离已记录在 **`docs/adr/0015-wip-ahead-of-roadmap.md`（ADR-0053）**，处置为「保留代码 + 补写文档」。
> 本文件与同目录 `plan.md` / `tasks.md` / `acceptance.md` 即该补写动作，目的只有一个：
> **让文档重新等于代码**。文中所有契约均以 2026-09-02 的真实代码为准，不做任何"应当如此"的宣称。

---

## 1. 背景与目标

在 013 之前，下单链路**完全不碰库存**：`OrderApplicationService` 只校验 SKU 可售、冻结价格快照、创建支付意图。
这意味着同一 SKU 可以被无限下单，系统没有任何"货"的概念。

本 Feature 建立「**下单预占 → 支付成功才扣减 → 失败/超时释放**」的三段式库存控制，
并把 `total = available + reserved + sold` 变成**领域层强制的不变量**（而非口头约定）。

**设计目标**：

1. **不超卖**：`available` 不会被扣成负数——DB 条件更新为最后防线。
2. **不漏卖**：预占后若未成交，必须回补 `available`，且回补必须幂等、可重试。
3. **全程可解释**：任何时刻 `available + reserved + sold` 恒等于 `total`，账可以从领域对象直接读出来。

**非目标**（明确排除）：

- ❌ 不新建 `inventory-service`（ADR-0041 已裁决放 catalog 内，宪章 IV 禁止无理由新增微服务）。
- ❌ 不做退款导致的库存回补（退款是独立 Feature，本期的 `confirm` 不可逆）。
- ❌ 不引入 MQ / RocketMQ 延迟消息（宪章 IV 排除，超时机制另选，见 ADR-0043）。

---

## 2. 归属决策与铁律

### 2.1 归属（ADR-0041）

库存是 **SKU 的销售属性**，与商品同属 catalog Bounded Context。故 `Stock` / `StockReservation` 聚合
放在 **catalog-service** 的 domain 层，order-service 经 Feign 同步 RPC 调用，**不独立成服务**。

### 2.2 铁律

```text
total = available + reserved + sold      （且三项均 ≥ 0）
```

| 阶段 | 触发点 | 变更 |
| --- | --- | --- |
| 下单 | `OrderApplicationService.doCreateOrder` | `available -= n; reserved += n` |
| 支付成功 | `OrderApplicationService.onPaymentSucceeded` | `reserved -= n; sold += n` |
| 支付失败 / 超时关单 | `releaseStockForOrder` / `OrderTimeoutScheduler` | `reserved -= n; available += n` |

任何变更必须**同增同减**，禁止只改一个字段——由 `Stock.assertInvariant()` 在每次状态迁移后强制。

### 2.3 超卖的三道防线

| # | 防线 | 位置 | 说明 |
| --- | --- | --- | --- |
| 1 | **领域不变量** | `Stock.assertInvariant()` | 任何破坏 `total = available + reserved + sold` 或产生负值的迁移，直接抛 `STATE_TRANSITION_VIOLATION`，**不落库** |
| 2 | **MyBatis-Plus 乐观锁（防并发覆盖）** | `StockEntity.version`（继承 `BaseEntity` 的 `@Version`）+ `OptimisticLockerInnerInterceptor` | 并发双方基于同一快照各自计算，后提交者 `updateById` 影响行数 = 0 → 抛 `CONFLICT`（409）。**因此不会超卖，但代价是后到者失败而非等待** |
| 3 | **Redis Lua 预扣（挡在 DB 前）** | 014 `SeckillStockService` | 秒杀品先过配额闸门，把洪峰挡在 DB 之外 |

> ⚠️ **与草案的差异（如实记录）**：`next-stage-design.md` §6.3 的第 1 道防线写的是
> `UPDATE stock SET available = available - ? WHERE sku_id = ? AND available >= ?`（**靠影响行数判定的原子条件更新**）。
> 实现**未采用**该 SQL——`MybatisStockRepository.save` 走的是 MyBatis-Plus 的 `updateById` + 乐观锁。
> 二者都能保证不超卖，但语义不同：条件更新是「判余量 + 扣减」一步原子（失败＝**真的没货**）；
> 乐观锁是「读快照 → 内存校验 → 提交」，冲突时失败原因是**版本过期**而非余量不足（错误码同为 409）。
> 在高争用下乐观锁重试成本更高。是否改回条件更新列为 TODO，**本次不做**（保持代码现状，先消除文档漂移）。

---

## 3. 关键用户故事

- **US1 下单即占货**：下单成功后该 SKU 的 `reserved` 增加、`available` 减少，其他买家看到的可售量同步减少。
- **US2 支付成功才真扣**：支付成功回调后 `sold` 增加；支付前 `sold` 不变（预付款不占"已售"）。
- **US3 失败/超时必回补**：支付失败或订单超时未付，预占被释放回 `available`，且可重复触发不重复回补。
- **US4 重复事件不破账**：重复支付成功回调、重复释放、重复超时扫描，都只生效一次。
- **US5 不变量可自检**：任一时刻读 `Stock` 均满足 `total = available + reserved + sold`；破坏即抛异常而非静默。

---

## 4. 功能需求（FR）

### 4.1 领域层

- **FR-001** `Stock` MUST 以 **SKU 为粒度**维护 `total / available / reserved / sold`，并提供 `Long id`、`Integer version`。
- **FR-002** `Stock` MUST 在**每一次状态迁移后**执行 `assertInvariant()`：违反 `total = available + reserved + sold` 或任一项为负时抛 `STATE_TRANSITION_VIOLATION`（`BizException`）。
- **FR-003** `Stock.reserve(qty)` MUST 在 `available < qty` 时抛 `CONFLICT`（→ HTTP **409**），消息含 `sku / available / requested`；`qty <= 0` 抛 `INVALID_ARGUMENT`。
- **FR-004** `Stock.confirm(qty)` MUST 在 `reserved < qty` 时抛 `CONFLICT`（不允许把未预占的量确认为已售）。
- **FR-005** `Stock.release(qty)` MUST 在 `reserved < qty` 时抛 `CONFLICT`（不允许超量回补导致 `available > total`）。
- **FR-006** `Stock` MUST 提供 `rehydrate(...)` 用于持久化重建（绕过创建期 `available = total` 初始化，但不绕过不变量校验）。

### 4.2 预占记录与幂等

- **FR-007** `StockReservation` MUST 以 `reservationId` 为**主键即幂等键**，字段含 `skuId / quantity / status / deductId`，状态机：`PENDING → CONFIRMED | RELEASED`。
- **FR-008** `reserve` MUST 幂等：
  - 已存在且仍 `PENDING` → **直接返回**（吸收）；
  - 已 `RELEASED` → **删除旧记录后重新预占**（允许同一键二次下单）；
  - 已 `CONFIRMED` → 抛 `CONFLICT`（已成交的预占不可被再次预占）。
- **FR-009** `confirm(reservationId, skuId, quantity, deductId)` MUST 幂等：**已 `CONFIRMED` 直接返回**（吸收回调重放）；`reservationId` 不存在抛 `NOT_FOUND`（→ 404）。
- **FR-010** `release` MUST 幂等：无记录、`RELEASED`、`CONFIRMED` 三种情况均**直接返回**（已确认的预占不因"释放"回滚，退款属独立 Feature）。
- **FR-011** 预占幂等键 MUST 为 `order:{orderId}:sku:{skuId}`，且该公式 MUST 在**三处保持一致**：`OrderApplicationService.reservationId()`、`OrderTimeoutScheduler.reservationId()`、`onPaymentSucceeded`。
  > 客户端 `Idempotency-Key` **不参与**库存键构造（仅用于下单入口去重，见 012）。

### 4.3 API

- **FR-012** catalog MUST 暴露内部端点 `POST /internal/stock/{seed,reserve,confirm,release}`（`StockController`），供 order-service Feign 调用与演示播种。
- **FR-013** 异常映射 MUST 复用 `common-core` 的 `GlobalExceptionHandler`：`CONFLICT → 409`、`NOT_FOUND → 404`、`INVALID_ARGUMENT → 400`。
- **FR-014** order-service MUST 经 `CatalogClient` 端口（生产实现 `CatalogFeignClient`）调用上述端点，**领域层不感知 HTTP**。

### 4.4 生命周期编排（order-service）

- **FR-015** 下单 MUST 对**每个明细行**依次执行「预占库存 → 加入回滚清单」，任一行失败 MUST 回滚**已成功的行**（`releaseStock`）+ 撤销订单。
- **FR-016** 下单成功（进入 `PENDING_PAYMENT`）MUST 登记订单超时（`timeoutScheduler.schedule(orderId)`）。
- **FR-017** 支付成功回调 `onPaymentSucceeded` MUST：`order.markPaid()` 返回 `false`（已 PAID）时**直接返回**（不重复确认库存）；否则确认扣减，幂等键 = `paymentId`。
- **FR-018** 订单超时调度 MUST 在订单仍处于 `PENDING_PAYMENT / PENDING_CONFIRMATION` 时释放预占并取消订单；已进入终态则跳过。
- **FR-019** 超时扫描 MUST 在处理后 **无论成败都 `ZREM`** 该 member，避免同一 orderId 被反复处理。

---

## 5. 领域模型与数据契约

### 5.1 `Stock`（catalog domain）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 代理主键 |
| `skuId` | `Long` | 业务键（唯一） |
| `total` | `long` | 总库存 |
| `available` | `long` | 可售 |
| `reserved` | `long` | 已预占 |
| `sold` | `long` | 已售出 |
| `version` | `Integer` | 乐观锁 |

### 5.2 `stock` 表（`deployment/schema/02-catalog-schema.sql`）

```sql
CREATE TABLE IF NOT EXISTS stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    total BIGINT NOT NULL,
    available BIGINT NOT NULL,
    reserved BIGINT NOT NULL,
    sold BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.3 `stock_reservation` 表

```sql
CREATE TABLE IF NOT EXISTS stock_reservation (
    reservation_id VARCHAR(64) NOT NULL,   -- 幂等键：order:{orderId}:sku:{skuId}
    sku_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,           -- PENDING / CONFIRMED / RELEASED
    deduct_id VARCHAR(64),                 -- 支付单号，确认时写入
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (reservation_id),
    KEY idx_stock_reservation_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.4 内部 REST 契约

| 端点 | 方法 | 请求体 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| `/internal/stock/seed` | POST | `{skuId, total}` | 200（已存在则幂等跳过） | — |
| `/internal/stock/reserve` | POST | `{reservationId, skuId, quantity}` | 200 | 409 库存不足 / 409 已 CONFIRMED / 404 SKU 无库存记录 |
| `/internal/stock/confirm` | POST | `{reservationId, skuId, quantity, deductId}` | 200 | 404 无预占记录 |
| `/internal/stock/release` | POST | `{reservationId, skuId, quantity}` | 200 | 404 SKU 无库存记录 |

---

## 6. 关键时序

### 6.1 下单（预占）

```text
OrderController.createOrder
  └─ OrderApplicationService.doCreateOrder
       ├─ catalogClient.getSku(...) ×N        // 校验可售 + 价格快照 + 币种一致
       ├─ order/transaction 落库（PENDING_CONFIRMATION）
       └─ for each item:
            ├─ catalogClient.trySeckillDeduct(skuId, qty)   // 014 秒杀闸门，失败即抛
            └─ catalogClient.reserveStock(reservationId, skuId, qty)
       ├─ order.confirm() → PENDING_PAYMENT
       ├─ timeoutScheduler.schedule(orderId)   // 登记超时（013/014 共用 ZSet）
       └─ paymentGateway.createPayment(...)    // order → payment 同步 RPC
  异常路径：逐行 releaseStock 回滚 + 撤销已预占的秒杀配额（014）+ order.cancel()
```

### 6.2 支付成功（确认扣减）

```text
onPaymentSucceeded(request)
  ├─ order.markPaid(paymentId) —— 返回 false ⇒ 直接 return（幂等吸收，库存亦不重复确认）
  ├─ transaction.succeed()
  └─ for each item: catalogClient.confirmStock(reservationId, skuId, qty, deductId=paymentId)
```

### 6.3 超时 / 失败（释放）

```text
OrderTimeoutScheduler.processExpired()        // @Scheduled，poll-millis 默认 5000
  ├─ ZRANGEBYSCORE order:timeouts 0 now
  └─ for each orderId:
       ├─ 状态非 PENDING_* ⇒ 跳过
       ├─ releaseStock(reservationId, skuId, qty)
       ├─ rollbackSeckill(skuId, qty)          // 014 配额回补，缺了会永久少卖
       ├─ order.cancel() + save
       └─ finally: ZREM（无论成败）
```

---

## 7. 验收标准（SC）

- **SC-001** 领域不变量：`Stock` 任意状态迁移后 `total = available + reserved + sold` 且三项非负；构造违反即抛 `STATE_TRANSITION_VIOLATION`。
- **SC-002** 预占：`available < qty` 时抛 `CONFLICT`（409），且**库存数值不变**。
- **SC-003** 幂等：同一 `reservationId` 重复 `reserve`（仍 PENDING）只扣一次；重复 `confirm` 只扣一次；重复 `release` 只回补一次。
- **SC-004** 已 `CONFIRMED` 的预占不可被 `reserve` 覆盖（抛 `CONFLICT`）、也不被 `release` 回滚。
- **SC-005** 端到端：下单 → `available↓ / reserved↑`；支付成功 → `reserved↓ / sold↑`；超时 → `reserved↓ / available↑`。
- **SC-006** 超时扫描：到期订单被取消并从 ZSet 移除；已支付/已取消订单被跳过；`finally` 分支保证失败也 `ZREM`。
- **SC-007** `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁）。
- **SC-008** 并发防覆盖：两个请求基于同一 `version` 快照各自预占，后提交者 `updateById` 影响行数 = 0 → 抛 `CONFLICT`（409），库存**不被重复扣减**。

---

## 8. 已知限制（诚实标注）

| # | 限制 | 影响 | 记录位置 |
| --- | --- | --- | --- |
| L1 | **Redis 不可用时超时能力降级**：扫描仅记日志跳过本轮，订单不会被自动取消 → 悬挂预占需人工或对账兜底 | 库存可能长期被占（少卖） | ADR-0043 |
| L2 | **多实例重复扫描未处理**：ZSet 时间轮在分布式部署下多实例会重复 `processExpired`（靠 `release` 幂等兜底，但仍非最优） | 重复调用、轻微放大 | ADR-0043 / ADR-0045 |
| L3 | **无库存对账**：`next-stage-design` §6.3 提出的「`sold + reserved` 与订单/支付事实定期核对」**未实现**，三道防线的第 3 道缺失 | 长期漂移无法自愈 | 待单独立项 |
| L4 | **与 011/012 纠缠**：`order-service` 同时承担收银台跳转（011）、入口幂等（012）与库存编排（013），提交时同批，**无法干净拆分成阶段纯提交**（拆分会导致 master 中间态不可编译） | 提交粒度粗 | ADR-0053 |
| L5 | **`reserve` 不校验重复请求的 quantity 一致性**：同 `reservationId` 以不同数量重复预占，直接吸收首次结果 | 极端情况下数量以首次为准 | `StockApplicationService.reserve` |
| L6 | **退款不回补库存**：`release` 对已 `CONFIRMED` 的预占直接返回 | 退款后库存不回补 | 本期非目标，退款 Feature |

---

## 9. 依赖与前置条件

- MySQL 8（`catalog` 库，含 `stock` / `stock_reservation` 表，见 `deployment/schema/02-catalog-schema.sql`）。
- Redis（超时 ZSet；与 014 缓存/秒杀共用同一实例）。
- order-service → catalog-service 同步 RPC 可达（`services.catalog.url`，默认 `http://localhost:8082`）。

---

## 10. 关联 ADR

| 编号 | 标题 | 结论 |
| --- | --- | --- |
| ADR-0041 | 库存域归属 | `Stock` 放 catalog，**不建** `inventory-service` |
| ADR-0042 | 库存扣减时机 | 下单预占 → 支付成功才扣 → 失败/超时释放 |
| ADR-0043 | 订单超时释放机制 | Redis ZSet 时间轮（MQ 被宪章排除） |
| ADR-0045 | Redis 用途边界 | Redis 非数据源，可全部丢失 |
| ADR-0053 | 超前落地偏离处置 | 保留代码 + 补写文档 |

均收录于 `docs/adr/0014-next-stage-decisions.md`（0041~0046）与 `docs/adr/0015-wip-ahead-of-roadmap.md`（0053）。
