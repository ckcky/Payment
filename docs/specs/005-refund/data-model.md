# Data Model: Refund（部分退款缺口补齐）

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 本文件**只定义补齐部分退款与后处理追踪所需的实体变更与不变量**，不重复已稳定的既有模型（完整基线见 `docs/architecture/systems/refund-service.md` §2）。
> 标注：无标记 = 已实现；`[改]` = 本 Feature 修改；`[新]` = 本 Feature 新增。

> ## ⛔ 负责人裁决（2026-08-30）· 落地（2026-08-31）：部分退款不做，代码已回退
>
> **ADR-0016 = Rejected。** 本节中所有 `refundedAmountMinor` / `refunded_amount_minor` / 「`PARTIALLY_SUCCEEDED` 可达」相关内容
> **仅作为历史决策记录保留，不代表当前代码状态**。当前实现：
>
> - `Refund` **无** `refundedAmountMinor` 字段；DDL **无** `refunded_amount_minor` 列。
> - 累计口径**一律按申请额 `amountMinor`**（含在途 `PROCESSING` / `UNKNOWN` 保守占位），见下方 §3 的修订版。
> - `PARTIALLY_SUCCEEDED` 枚举与 `partiallySucceed(long)` **保留但无调用方**（删除枚举会让历史行在 `RefundStatus.valueOf` 处抛异常，打挂退款受理路径）。
> - 后处理与记账金额一律取 `amountMinor`（成功退款恒为全额）。

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
| **amountMinor** | long | 已实现 | **申请**退款金额（最小货币单位）；不变量 `> 0`（`Refund.java:39`）。**ADR-0016 回退后：它同时就是「已确认退款金额」**——成功退款恒为全额 |
| ~~refundedAmountMinor~~ | ~~long~~ | ❌ **ADR-0016 已回退** | 曾新增的「已确认退款金额」字段，**2026-08-31 已从领域/实体/DDL 全链路删除** |
| currencyCode / reason / idempotencyKey | String | 已实现 | 币种 / 原因 / 幂等键（`uk_refunds_idempotency_key`） |
| status | RefundStatus | 已实现 | `PARTIALLY_SUCCEEDED` **保持不可达**（ADR-0016 裁决不做；枚举保留仅为避免 `valueOf` 对历史行抛异常） |
| failureReason / version | String / Integer | 已实现 | 失败原因 / 乐观锁 |

**DDL 变更（`deployment/schema/06-refund-schema.sql`）**：

```sql
-- ❌ ADR-0016 已否决（负责人决议「部分退款不做」）：refunds 不再有 refunded_amount_minor 列。
-- 已部署环境若曾加过该列，需手工执行下迁移：
--   ALTER TABLE `refund`.`refunds` DROP COLUMN `refunded_amount_minor`;
```

> 该列曾属**新增关键资金字段**（Constitution §8.3），ADR-0016 裁决不做后**已回退**：
> 领域层字段、MyBatis 实体映射、DDL、测试 Schema 一并删除，契约 DTO 也回到无该字段的形态。
> 重新开放部分退款时，本节与 ADR-0016 内的「回退落地记录」即为复原清单。

## 3. 累计额度不变量（防超退，H1）

**当前口径（ADR-0016 回退后，`RefundApplicationService` 内实现）**：

```text
cumulative(paymentId) = Σ over Refund r where status ∈ 计额状态:
    r.status ∈ {SUCCEEDED, PARTIALLY_SUCCEEDED, PROCESSING, UNKNOWN}  →  r.amountMinor （一律按申请额）
    r.status ∈ {FAILED, REJECTED, CLOSED}                              →  不计入
约束：cumulative + requestedMinor <= paidAmountMinor
```

> 原设计（已随 ADR-0016 作废）曾区分「终态按已确认额、在途按申请额占位」。
> 部分退款不做后，`amountMinor` 即最终金额，故**统一按申请额累计**，不再分态。
> 在途（`PROCESSING` / `UNKNOWN`）仍按申请额**保守占用**，这是防并发超退（H1）的关键。

**为什么在途按申请额占位**：在途退款尚无已确认金额，若按 0 计，并发多笔在途退款可各自通过校验并累计超退（资金正确性 > 一切，Constitution 冲突优先级 1）。

**Schema 级不变量（MUST 成立）**：

| # | 不变量 | 校验位置 |
|---|---|---|
| INV-1 | `0 < amountMinor` | `Refund` 构造（已实现，`Refund.java:39`） |
| INV-2 | `0 < amountMinor`（申请额恒为正，且成功时即为已退金额） | `Refund` 构造（已实现） |
| INV-3 | 同 `paymentId`：`cumulative + requested <= paidAmountMinor` | `RefundPolicy.decide` + `refund_intake_locks` 悲观锁（已实现） |

> ❌ 原 INV-2~INV-5（`refundedAmountMinor` 的范围/与状态的一致性约束）**已随 ADR-0016 回退一并删除**。
> 重新开放部分退款时须原样恢复这四条不变量。

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
REQUESTED --process()--> PROCESSING --succeed()-------> SUCCEEDED
                              |      \--fail(reason)---> FAILED
                              \--markUnknown(reason)--> UNKNOWN
REQUESTED --reject(reason)--> REJECTED
SUCCEEDED / FAILED / REJECTED --close()--> CLOSED
UNKNOWN --succeed()/fail()--> SUCCEEDED / FAILED

                    ⛔ 以下迁移当前【无调用方】（ADR-0016 已否决，但未删除枚举）：
PROCESSING/UNKNOWN --partiallySucceed(r)--> PARTIALLY_SUCCEEDED
```

**ADR-0016 回退后的实际形态**：

1. `succeed()`：仅做 `PROCESSING`/`UNKNOWN` → `SUCCEEDED` 状态迁移，**不写任何金额字段**（成功恒为全额，`amountMinor` 即已退金额）。
2. `partiallySucceed(long)`：**保留但无调用方**。Javadoc 已显式标注「ADR-0016 已否决」。
   保留理由：若删除 `PARTIALLY_SUCCEEDED` 枚举，历史 `status='PARTIALLY_SUCCEEDED'` 行会在
   `MybatisRefundRepository#toDomain` 的 `RefundStatus.valueOf(...)` 处抛 `IllegalArgumentException`，
   连带打挂 `findByPaymentId` → **整条退款受理路径**。保留一个空方法远低于数据迁移风险。

**未改变的语义**：终态吸收（`SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED/REJECTED/CLOSED` 吸收一切迟到冲突结果，`transitionTo` 返回 `false`）；`markUnknown` 仅 `PROCESSING → UNKNOWN`；`close()` 仅终态可关闭。

## 6. RefundItem（`domain/RefundItem.java`，不变）

`record RefundItem(String orderItemId, long amountMinor)` —— 明细金额即**申请**金额，也是最终退款金额（部分退款不做，无需跟踪逐明细的已确认金额）。

## 7. 契约侧字段变更（跨服务，向后兼容）

| 契约 DTO | 变更 | 兼容性 |
|---|---|---|
| `RefundAttemptResponse` | ❌ 回退：回到 3 分量 `(refundNo, status, channelReference)` | 与既有实现一致 |
| `RefundFulfillmentRequest` / `Response` | ✅ 新增（refund → fulfillment，ADR-0017 Accepted） | 新端点 |
| `RefundResponse` | ❌ 回退：回到 7 分量，不含 `refundedAmountMinor` | 与既有实现一致 |
| `PostingRequest` | 复用既有（refund → ledger），`sourceType=REFUND` | 不变 |

详见 [contracts/refund-orchestration.md](contracts/refund-orchestration.md)。
