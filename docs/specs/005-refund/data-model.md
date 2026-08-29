# Data Model: Refund（部分退款缺口补齐）

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 本文件**只定义补齐部分退款与后处理追踪所需的实体变更与不变量**，不重复已稳定的既有模型（完整基线见 `docs/architecture/systems/refund-service.md` §2）。
> 标注：无标记 = 已实现；`[改]` = 本 Feature 修改；`[新]` = 本 Feature 新增。

## 1. 实体关系

```text
Refund (1) ──── (N) RefundItem                      [已实现]
Refund (1) ──── (N) RefundPostProcessAttempt        [新]  目标 ∈ {FULFILLMENT, ENTITLEMENT, LEDGER}
Refund (1) ──── (0..1) Posting（ledger-service，经 RPC，不落本地外键）
```

同一 `paymentId` 可对应多笔 `Refund`；累计额度受 §3 不变量约束。

## 2. Refund（聚合根，`domain/Refund.java`）

| 字段 | 类型 | 状态 | 说明 |
|---|---|---|---|
| id | Long | 已实现 | 退款 ID |
| orderId / paymentId / userId | String / Long / String | 已实现 | 关联引用 |
| **amountMinor** | long | 已实现 | **申请**退款金额（最小货币单位）；不变量 `> 0`（`Refund.java:39`） |
| **refundedAmountMinor** | long | **[改] 新增** | **已确认**退款金额；不变量 `0 <= refundedAmountMinor <= amountMinor`；初始 0 |
| currencyCode / reason / idempotencyKey | String | 已实现 | 币种 / 原因 / 幂等键（`uk_refunds_idempotency_key`） |
| status | RefundStatus | 已实现 + **[改]** | `PARTIALLY_SUCCEEDED` 由不可达变为可达（见 §5） |
| failureReason / version | String / Integer | 已实现 | 失败原因 / 乐观锁 |

**DDL 变更（`deployment/schema/06-refund-schema.sql`）**：

```sql
ALTER TABLE refunds
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0 AFTER amount_minor;
```

> 该列属**新增关键资金字段**，须负责人确认（Constitution §8.3 / ADR-0016）。存量行默认 0，语义为「尚未确认任何退款金额」；历史 `SUCCEEDED` 行需按 ADR-0016 的回填策略处理（建议：`SUCCEEDED` 回填为 `amount_minor`，其余保持 0）。

## 3. 累计额度不变量（防超退，H1）

`RefundPolicy.decide` 的累计口径（`[改]`，`domain/RefundPolicy.java`）：

```text
cumulative(paymentId) = Σ over Refund r where status ∈ 计额状态:
    r.status ∈ {SUCCEEDED, PARTIALLY_SUCCEEDED}  →  r.refundedAmountMinor   （已确认额）
    r.status ∈ {PROCESSING, UNKNOWN}              →  r.amountMinor          （在途占位）
    r.status ∈ {FAILED, REJECTED, CLOSED}         →  不计入
约束：cumulative + requestedMinor <= paidAmountMinor
```

**为什么在途按申请额占位**：在途退款尚无已确认金额，若按 0 计，并发多笔在途退款可各自通过校验并累计超退（资金正确性 > 一切，Constitution 冲突优先级 1）。

**Schema 级不变量（MUST 成立）**：

| # | 不变量 | 校验位置 |
|---|---|---|
| INV-1 | `0 < amountMinor` | `Refund` 构造（已实现，`Refund.java:39`） |
| INV-2 | `0 <= refundedAmountMinor <= amountMinor` | `Refund.partiallySucceed/succeed`（新增） |
| INV-3 | `refundedAmountMinor == 0` 当 `status ∈ {REQUESTED, PROCESSING, UNKNOWN, REJECTED, FAILED}` | 领域状态迁移（新增） |
| INV-4 | `refundedAmountMinor == amountMinor` 当 `status == SUCCEEDED` | 领域状态迁移（新增） |
| INV-5 | `0 < refundedAmountMinor < amountMinor` 当 `status == PARTIALLY_SUCCEEDED` | 领域状态迁移（新增） |
| INV-6 | 同 `paymentId`：`cumulative + requested <= paidAmountMinor` | `RefundPolicy.decide` + `refund_intake_locks` 悲观锁（已实现 / 口径扩展） |

> INV-2~INV-5 为领域层强校验；数据库层以 `BIGINT NOT NULL DEFAULT 0` 保证非空，金额上限与状态一致性由领域状态机唯一入口保证（Constitution §V.2）。

## 4. RefundPostProcessAttempt（后处理尝试，`[新]`）

支撑「后处理失败可独立追踪」（FR-005）。每次出站后处理/记账调用落一条记录，**失败不回滚退款成功**。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| refund_id | BIGINT NOT NULL | 退款引用，索引 `idx_pp_refund` |
| target | VARCHAR(16) NOT NULL | `FULFILLMENT` / `ENTITLEMENT` / `LEDGER` |
| idempotency_key | VARCHAR(128) NOT NULL | 幂等依据（如 `refund-42:FULFILLMENT`），唯一 `uk_pp_idem` |
| status | VARCHAR(16) NOT NULL | `SUCCEEDED` / `FAILED` / `SKIPPED`（下游无可执行动作） |
| attempt_count | INT NOT NULL DEFAULT 1 | 尝试次数（同步有限重试，[目标] 上限 3） |
| failure_reason | VARCHAR(255) | 失败原因 |
| created_at / updated_at / version | — | 审计 + 乐观锁 |

```sql
CREATE TABLE IF NOT EXISTS refund_post_process_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    target VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    failure_reason VARCHAR(255),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pp_idem (idempotency_key),
    KEY idx_pp_refund (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **唯一约束 `uk_pp_idem`** 保证同一「退款 + 目标」只产生一条成功记录（重复收敛被幂等吸收）。
- 失败记录**保留并可重试**：重试沿用同一 `idempotency_key` 更新 `attempt_count` 与 `status`；也可由对账/人工按 `refund_id` 查询失败清单。

## 5. 状态机变更（`domain/RefundStatus.java` / `Refund.java`）

```text
REQUESTED --process()--> PROCESSING --succeed()---------------> SUCCEEDED            (refundedAmountMinor = amountMinor)
                              |      \--partiallySucceed(r)---> PARTIALLY_SUCCEEDED  (0 < r < amountMinor)  [改：可达]
                              |      \--fail(reason)----------> FAILED               (refundedAmountMinor = 0)
                              \--markUnknown(reason)---------> UNKNOWN              (refundedAmountMinor = 0)
REQUESTED --reject(reason)--> REJECTED
SUCCEEDED / PARTIALLY_SUCCEEDED / FAILED / REJECTED --close()--> CLOSED
UNKNOWN --succeed()/partiallySucceed()/fail()--> SUCCEEDED / PARTIALLY_SUCCEEDED / FAILED
```

**变更点（仅 2 处，均经 `transitionTo` 唯一入口）**：

1. `partiallySucceed(long refundedMinor)`：校验 `0 < refundedMinor < amountMinor` 后迁移并写入 `refundedAmountMinor`（违反则抛 `AMOUNT_INVARIANT_VIOLATION`，不静默降级为 SUCCEEDED/FAILED）。
2. `succeed()`：迁移成功后置 `refundedAmountMinor = amountMinor`（保持 INV-4）。

**未改变的语义**：终态吸收（`SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED/REJECTED/CLOSED` 吸收一切迟到冲突结果，`transitionTo` 返回 `false`）；`markUnknown` 仅 `PROCESSING → UNKNOWN`；`close()` 仅终态可关闭。

## 6. RefundItem（`domain/RefundItem.java`，不变）

`record RefundItem(String orderItemId, long amountMinor)` —— 明细金额仍为**申请**金额，不记录逐明细的已确认金额（MVP 只跟踪整单已确认金额；按明细跟踪属 `[待定]`，见 ADR-0016 备选方案 B）。

## 7. 契约侧字段变更（跨服务，向后兼容）

| 契约 DTO | 变更 | 兼容性 |
|---|---|---|
| `RefundAttemptResponse` | 新增 `refundedAmountMinor`（渠道实际退款金额） | 向后兼容（新增字段，默认值语义由 ADR-0016 定） |
| `RefundFulfillmentRequest` / `Response` | 新增（refund → fulfillment） | 新端点 |
| `RefundResponse` | 新增 `refundedAmountMinor` | 向后兼容 |
| `PostingRequest` | 复用既有（refund → ledger），`sourceType=REFUND` | 不变 |

详见 [contracts/refund-orchestration.md](contracts/refund-orchestration.md)。
