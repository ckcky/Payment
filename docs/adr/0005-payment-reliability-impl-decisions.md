# ADR-0012 ~ ADR-0015：支付可靠性（Feature 003）实现期决策

**日期**: 2026-08-28
**状态**: **Proposed（按「最简方式」默认实现，待负责人确认）**
**关联**: ADR-0003~0007（已 Accepted）、`docs/specs/003-payment-reliability/`

> 本文件记录 **003 实现期**遇到的分歧/歧义点。按负责人指示：实现先按「最简方式」推进，
> 同时把决策点记录在此供决策。**确认前这些决策已生效运行，确认后如被否决需回滚对应实现。**

---

## ADR-0012：重试的错误分类来源（UNKNOWN 不重试）

**状态**: Proposed

### 背景

FR-005 要求「幂等调用因**瞬时**错误失败时重试」，FR-006 要求「**硬拒绝**不重试」。
既有 `ChannelResult` 只有 `SUCCESS / FAILURE / UNKNOWN` 三态，无法区分「瞬时失败」与「硬拒绝」。

### 决策（最简）

- 在 `ChannelResult` 增加第 4 个分量 `errorType`（领域枚举 `PaymentAttemptErrorType`）。
- `failure(ref, reason)` → `HARD`（沿用既有语义，硬拒绝不重试）。
- 新增工厂 `transientFailure(reason)` → `TRANSIENT`（幂等可重试）。
- `unknown(reason)` → `UNKNOWN`，**明确不重试**，直接进 UNKNOWN，由 US2 主动查询收敛。

### 理由

- 最小改动：既有 `failure(...)` 调用点语义不变，无需改动既有测试。
- UNKNOWN 不重试符合 Constitution §V（不把「未确认」当成功或失败）与 ADR-0004（超时进未知）；
  对渠道不明确的结果重试**可能重复扣款**，风险高于收益。
- 数据模型（data-model.md）原把 `error_type=UNKNOWN` 归入「同 TRANSIENT 耗尽路径」，
  本决策**偏离**该描述 —— 这是需要负责人确认的**分歧点**。

### 备选（若否决）

- 方案 B：`UNKNOWN` 也参与重试直到耗尽再进 UNKNOWN（贴合 data-model 原文，但增加重复扣款风险）。

### 影响

`ChannelResult` 记录结构变更（新增分量）；`MockChannelAdapter` 新增 `Scenario.TRANSIENT`。

---

## ADR-0013：重试调度的载体（字段最小化）

**状态**: Proposed

### 背景

data-model.md 计划为 `payment_attempts` 增加 `attempt_index`、`error_type`、`next_retry_at`、`finished_at` 四列。实现时希望尽量减少对领域模型与 Schema 的改动。

### 决策（最简）

- **不新增** `attempt_index`：复用既有 `retryCount`（0 为首次，每安排一次重试 +1）。
- **不新增** `finished_at`：复用既有 `respondedAt`。
- **新增** `error_type`（可重试性）+ `next_retry_at`（下次重试时刻，NULL=不再重试）两列。
- 仓储新增 `findRetryableDue(now)`：`next_retry_at IS NOT NULL AND next_retry_at <= now`。
- 退避取 `ReliabilityConfig.retryBackoff`（默认 1s/2s/4s），第 n 次重试取序列第 n 项，越界取最后一项。
- 上限判定：`attemptsMade = retryCount + 1`；`attemptsMade < retryMaxAttempts` 才安排重试。
  默认上限 3 表示**含首次共 3 次**渠道调用（1 次首发 + 2 次重试）。

### 理由

字段最少、SQL 最少；`retryCount` 与 `respondedAt` 语义天然等价，避免冗余列与双写不一致。

### 影响

`PaymentAttempt` 领域新增 2 字段 + `recordRetry()`；`PaymentAttempt.rehydrate` 签名扩展；
`payment_attempts` 新增 2 列（生产库需执行增量 DDL，见 `deployment/schema/03-payment-schema.sql`）。

---

## ADR-0014：重试的幂等与事务边界（同 attempt 重放）

**状态**: Proposed

### 背景

data-model.md 描述「`error_type=TRANSIENT` 且未达上限 → **创建下一 attempt**」。但 `PaymentAttempt` 状态机中
`FAILED` 是终态；若在瞬时失败时先 `attempt.fail(...)`，后续重试会在同一 attempt 上被终态吸收。

### 决策（最简）

- 瞬时失败且未达上限时，**不应用失败结果**：支付保持 `PROCESSING`、尝试保持 `PENDING`，
  仅写入 `error_type` 与 `next_retry_at`。
- 重试时**在同一 attempt 上重放** `channel.charge`（同 `paymentId` + 同 attempt，幂等键不变），
  不创建新 attempt 行。
- 重试成功 → 走既有 `PaymentResultProcessor.applyAndNotify`（`attempt.accept` + `succeed`，只推进一次下游）。
- 重试耗尽且结果仍不确定 → `payment.markUnknown("RETRY_EXHAUSTED")` + `payment.retry_exhausted` 计数，
  **不进 FAILED**（FR-007）。

### 理由

- 避免创建大量 attempt 行，也让「一次支付尝试的重试历史」集中在一行，便于观测。
- 与既有状态机终态吸收规则一致，无需改状态机（不触发 Constitution §8.8 人类确认）。

### 备选（若否决）

- 方案 B：按 data-model 原文，每次重试新建 attempt（`attempt_index` 递增），历史更完整但需新增列与更多行。

### 影响

瞬时失败期间支付保持 `PROCESSING`，**不立即可见为失败**——运营需以「重试中」理解该状态。

---

## ADR-0015：UNKNOWN 真实收敛时长的度量方式

**状态**: Proposed

### 背景

`PaymentUnknownResolutionService` 当前以 `Duration.ZERO` 记录 `payment.unknown.duration`（spec Edge Cases 已记录为缺口），
因为领域聚合未携带「进入 UNKNOWN 的时刻」。data-model 建议复用 `BaseEntity.updatedAt` 推导。

### 决策（最简）

- **不复用 `updatedAt`**：该列会被后续任意保存覆盖，无法还原「进入 UNKNOWN 的时刻」。
- 在 `payments` 增加 `entered_unknown_at` 列，在所有 `markUnknown` 入口（超时扫描、重试耗尽、渠道 UNKNOWN 回调）写入。
- 收敛时以 `now - enteredUnknownAt` 产出真实时长。

### 理由

这是能产出**真实**时长的字段最少的方式；复用 `updatedAt` 会产出错误数据。

### 影响

`Payment` 领域新增 `enteredUnknownAt` 字段 + `rehydrate` 签名扩展；`payments` 新增 1 列。

---

## 待负责人确认清单

| ADR | 一句话决策 | 建议 |
|---|---|---|
| 0012 | UNKNOWN 渠道结果**不重试**，直接进 UNKNOWN | 建议接受（避免重复扣款） |
| 0013 | 复用 `retryCount`/`respondedAt`，只加 `error_type`+`next_retry_at` 两列 | 建议接受 |
| 0014 | 重试在**同一 attempt** 重放，不新建 attempt | 建议接受（若需完整重试历史改方案 B） |
| 0015 | 新增 `entered_unknown_at` 列度量真实收敛时长 | 建议接受 |
