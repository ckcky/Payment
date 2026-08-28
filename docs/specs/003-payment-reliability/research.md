# Research: 支付可靠性决策汇总（Phase 0）

> 本文件汇总本 Feature 的所有关键不确定点与架构决策，结论与 `docs/adr/0003~0007` 一一对应。ADR 当前状态均为 **Proposed**，须负责人按 Constitution §8 确认后进入实现。

## R1 — UNKNOWN 收敛触发机制（→ ADR-0003）

- **Decision**: 主动查询调度器 + 人工收敛端点双路径；被动回调仍兼容。达到查询尝试上限仍不明确则保持 UNKNOWN，转人工/对账。
- **Rationale**: 仅被动等待回调会使 UNKNOWN 永久悬空、阻塞结算；主动查询及时收敛。
- **Alternatives**: A. 仅被动回调（否决：悬空风险）；B. 仅对账（否决：实时性差）。

## R2 — 超时进入 UNKNOWN 的策略（→ ADR-0004）

- **Decision**: 进程内调度器扫描「PROCESSING 且距最近尝试超阈值」的支付→`markUnknown(超时)`；仅作用于 PROCESSING；阈值可配置，默认 30s；扫描间隔默认 10s；不猜成败。
- **Rationale**: 超时确定性进未知、对齐 §4.4；状态机唯一入口。
- **Alternatives**: A. 调用方标记（否决：绕过状态机）；B. 仅人工（否决：堆积）。

## R3 — 支付重试模型（→ ADR-0005）

- **Decision**: 仅幂等调用可重试；硬拒绝不重试直接 FAILED；调度器驱动（非内联循环），经 `PaymentAttempt` 记录序号，复用渠道「带幂等键调用」重放；默认上限 3 次、退避 1s/2s/4s；耗尽且不确定→UNKNOWN，明确失败→FAILED。
- **Rationale**: 瞬时故障自愈、不雪崩、不重复扣款；与 Constitution §4 / ADR-0001 一致。
- **Alternatives**: A. 内联同步重试（否决：阻塞/无弹性）；B. MQ 重试（否决：超出当前架构）。

## R4 — 人工收敛能力与权限/审计（→ ADR-0006）

- **Decision**: 受控内部端点 `POST /internal/payments/{id}/resolve`，仅作用于 UNKNOWN；强制理由 + `FINANCIAL_AUDIT`（操作人/前后状态/理由/时间）；权限沿用既有安全基线，生产级权限留 Phase 9；裁定成功复用 002 回写（只一次）。
- **Rationale**: UNKNOWN 有可问责兜底；不破坏状态机不变量。
- **Alternatives**: A. 无人工（否决：无法及时干预）；B. 可改任意状态（否决：绕过状态机/审计缺失）。

## R5 — 终态冲突策略（→ ADR-0007）

- **Decision**: 维持既有状态机不变量——终态（SUCCEEDED/FAILED/CLOSED）吸收迟到冲突；先 FAILURE 后 SUCCESS 保持 FAILED；误判走人工/对账显式修正，不自动覆盖。
- **Rationale**: 状态机可预测、无幽灵翻转；避免「已退款/已结算资金被错误翻转为成功」。
- **Alternatives**: A. 迟到成功覆盖已失败（否决：信任不可靠迟到结果、灾难性）。

## R6 — UNKNOWN 时长度量的实现位置（补充决策）

- **Decision**: UNKNOWN 真实收敛时长基于**持久化层 `BaseEntity` 时间戳**（`updatedAt` 在状态变为 UNKNOWN 时记录进入时刻，在离开 UNKNOWN 时记录收敛时刻）计算，**不向 Payment 领域聚合新增时间戳字段**，以避免触发 Constitution §8.8 领域模型变更。既有的 `PaymentUnknownResolutionService` 以 `Duration.ZERO` 记录时长的缺口在本 Feature 修复（改为持久化层计时）。
- **Rationale**: 满足 SC-005（真实时长可度量），同时不扩大领域模型边界。
- **Alternatives**: A. 领域聚合加 `enteredUnknownAt`/`resolvedAt`（否决：属 §8.8 领域模型变更，需单独确认）。

## 依赖与前置

- 依赖 002-payment-order-callback 的成功回写链路（人工裁定成功时复用）与既有 UNKNOWN 收敛（`PaymentUnknownResolutionService`）、支付状态机。
- 依赖 009 Observability Baseline 的 Micrometer/审计基础（指标与告警直接复用）。
