# Acceptance: Refund 退款（缺口补齐）

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

> 本文件为 Feature 验收清单。当前（2026-08-29）为**文档先行**阶段，**实现未开始**，全部未勾选。

## 功能验收

### US1 · 部分退款（缺口 G1）

- [ ] 渠道部分退回 → 落 `PARTIALLY_SUCCEEDED` 且记录 `refundedAmountMinor`（SC-001）
- [ ] 渠道全额退回 → 落 `SUCCEEDED`，`refundedAmountMinor == amountMinor`（SC-001）
- [ ] 渠道退回 0 / 超申请额 → 不落成功类状态，告警（Edge Cases）
- [ ] 累计额度口径正确：终态计已确认额、在途计申请额；超额申请落 `REJECTED` 且不发起渠道尝试（SC-002）
- [ ] 数据不变量 INV-1~INV-6 成立（data-model §3）

### US2 · 后处理编排（缺口 G2）

- [ ] 确认退款同时触发 fulfillment 撤销与 entitlement 吊销（SC-004）
- [ ] 新增 `POST /internal/fulfillments/on-refund` 可用且幂等（contracts §2）
- [ ] 后处理失败被记录为独立的 `RefundPostProcessAttempt`（含目标、原因、次数），可查询（SC-004）
- [ ] 后处理失败**不回滚**退款成功（SC-004 / ADR-0017）
- [ ] 旧静默路径（`catch (RuntimeException ignored)`）已被移除或替换（FR-005）
- [ ] `UNKNOWN` 退款不触发任何后处理（FR-007）
- [ ] fulfillment 已交付时返回 `SKIPPED`/`REJECTED`，不被强制改写（Constitution 边界 #6）

### US3 · 幂等与收敛边界（缺口 G3）

- [ ] 重复幂等键退款被吸收，不产生第二次渠道尝试（SC-003）
- [ ] `UNKNOWN` 退款不被重复发起渠道退款尝试（SC-003）
- [ ] `resolve` 对 `REQUESTED` 显式拒绝（`STATE_TRANSITION_VIOLATION`，含当前状态）（SC-003）
- [ ] `resolve` 对已终态幂等吸收；重复收敛只触发一次后处理与记账（SC-003）

### US4 · 记账接入（承接 004-ledger US2）

- [ ] 已确认退款在 ledger-service 留下 `sourceType=REFUND` 的平衡冲正 Posting（SC-005）
- [ ] 记账金额 = `refundedAmountMinor`（部分退款按实际金额，非申请额）（SC-005）
- [ ] 重复记账被幂等吸收（SC-005）
- [ ] 记账失败/超时不回滚退款成功，进入重试/对账兜底（SC-005 / ADR-0018）

### US5 · 可观测与对账事实

- [ ] 全部退款分支有指标：`refund.created/duplicate/rejected/succeeded/partially_succeeded/failed/unknown/post_process_failed`（SC-006）
- [ ] 每次资金状态迁移写入 `FINANCIAL_AUDIT`（含幂等键、金额、前后状态、traceId）（SC-006）
- [ ] `confirmed-facts` 覆盖已确认退款并以实际退款金额暴露（SC-006 / ADR-0016）

## 非功能验收

- [ ] 金额全程禁 `float`/`double`；金额不变量在受理/回传/记账三处分别校验（FR-011）
- [ ] 状态迁移全部经 `Refund.transitionTo` 唯一入口，无散落 `setStatus`（FR-012）
- [ ] 跨服务仅同步 RPC + 幂等，未引入 MQ / 2PC / XA（FR-013）
- [ ] Database-per-service：refund-service 只读写 `refund` Schema（FR-014）
- [ ] 全量测试（`./mvnw verify`）通过，含 Testcontainers 集成测试；既有 refund 测试未被删改以迎合实现（FR-017、Constitution §VIII.3/4）

## 决策验收（Constitution §8）

- [ ] ADR-0016~0018 经负责人确认并更新状态为 Accepted
- [ ] 新增资金字段 `refunds.refunded_amount_minor` 与新增表 `refund_post_process_attempts` 经确认（§8.3）
- [ ] 跨服务接口变更（`RefundAttemptResponse` 增字段、新增 fulfillment 端点）经确认（§8.4）
- [ ] 退款状态机变更（`PARTIALLY_SUCCEEDED` 可达）经确认（§8.8）
- [x] ADR 编号冲突已处理（本包重编号为 `0006-refund-decisions.md` / ADR-0016~0018，既有 `0005-payment-reliability-impl-decisions.md` 的 ADR-0012~0015 不变）

## 验收结论

- **状态**：未完成（待 ADR 确认 + 实现）
- **阻塞**：ADR-0016~0018 待负责人决策（T001 为实现门禁）
