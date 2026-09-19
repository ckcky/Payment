# Data Model: 支付可靠性（Phase 1）

> 仅涉及 `payment-service` 自有 Schema。不跨服务改表。金额一律 `long` 最小货币单位，不引入 float/double。

## Payment（支付聚合）

既有聚合 `Payment.java` 的状态机保持不变（ADR-0007）。本 Feature 仅补充**触发入口**与**持久化层**字段：

### 领域层（不变，仅新增方法语义说明）

- `markUnknown(reason)`：PROCESSING → UNKNOWN（既有，供超时/重试耗尽调用）。
- `succeed()` / `fail(reason)`：PROCESSING/UNKNOWN → 终态（既有，供收敛调用）。
- 终态（SUCCEEDED/FAILED/CLOSED）吸收迟到冲突（既有，ADR-0007）。

> 领域聚合**不新增**时间戳字段（避免 Constitution §8.8）。UNKNOWN 进入/离开时刻由持久化层 `BaseEntity`（createdAt/updatedAt）承载（见 R6）。

### 持久化层（payment 表新增/调整列）

| 列 | 类型 | 说明 |
|---|---|---|
| `entered_unknown_at` | `DATETIME` NULL | 进入 UNKNOWN 的时刻（由服务层在 `markUnknown` 后写入；亦可由 `BaseEntity.updatedAt` 推导，二选一，推荐直接使用 `updatedAt` 推导以减少字段） |
| `resolved_at` | `DATETIME` NULL | 离开 UNKNOWN（收敛/人工）的时刻，用于计算真实 UNKNOWN 时长 |
| `resolved_by` | `VARCHAR` NULL | 人工收敛操作人（自动收敛为空），资金审计维度 |
| `attempt_count` | `INT` NOT NULL DEFAULT 0 | 累计尝试次数（含重试），用于重试上限判断与观测 |

> 说明：为避免领域模型变更，推荐 `entered_unknown_at`/`resolved_at` 直接复用 `BaseEntity` 的 `updatedAt` 语义（状态变化即更新），`resolved_by` 仅人工场景写入。若团队偏好显式列，可在确认 ADR 时一并决定。

### 状态转换（不变量，来自既有状态机）

```text
PENDING --start()--> PROCESSING
PROCESSING --(超时/重试耗尽/渠道UNKNOWN)--> UNKNOWN
PROCESSING/UNKNOWN --succeed()--> SUCCEEDED
PROCESSING/UNKNOWN --fail()--> FAILED
SUCCEEDED/FAILED --close()--> CLOSED
终态(SUCCEEDED/FAILED/CLOSED) 吸收一切迟到冲突结果（返回 false，不触发事件）
```

## PaymentAttempt（支付尝试，1:N）

既有实体扩展，记录每次渠道调用（含重试）的结果，支撑重试调度与观测：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | |
| `payment_id` | BIGINT FK | 所属支付 |
| `attempt_index` | INT NOT NULL | 尝试序号，1 为首次，>1 为重试 |
| `status` | VARCHAR | `PENDING` / `SUCCEEDED` / `FAILED_TRANSIENT` / `FAILED_HARD` / `UNKNOWN` |
| `error_type` | VARCHAR NULL | `TRANSIENT`（可重试）/ `HARD`（硬拒绝，不重试）/ `UNKNOWN`（不确定） |
| `channel_code` | VARCHAR NULL | 渠道返回码 |
| `attempted_at` | DATETIME | 本次尝试发起时刻 |
| `next_retry_at` | DATETIME NULL | 计划下次重试时刻（退避计算），无则不重试 |
| `finished_at` | DATETIME NULL | 本次尝试结束时刻 |

### 重试判定规则（来自 ADR-0005）

- `error_type = HARD` → 不创建后续 attempt，支付直接 `FAILED`。
- `error_type = TRANSIENT` 且 `attempt_index < 上限(默认3)` → 创建下一 attempt，`next_retry_at = now + 退避(1s/2s/4s)`。
- `error_type = TRANSIENT` 且已达上限 → 不再重试，支付 `markUnknown`。
- `error_type = UNKNOWN`（渠道响应不明确）→ 同 TRANSIENT 耗尽路径，`markUnknown`。

## 关键校验（来自 spec FR）

- 仅 `PROCESSING` 可被超时扫描推进为 UNKNOWN（FR-001）。
- 终态支付的人工裁定被拒绝/吸收（FR-009，ADR-0007）。
- 所有迁移经状态机唯一入口 + 乐观锁（version）保护（FR-011）。
- 重试仅对幂等调用；硬拒绝 0 重试（FR-005/FR-006）。
