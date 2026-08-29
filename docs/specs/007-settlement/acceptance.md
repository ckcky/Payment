# Acceptance: Settlement 结算（缺口补齐）

**Feature**: `007-settlement` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

> 本文件为 Feature 验收清单。实现阶段完成后逐项勾选。当前（2026-08-29）处于**文档先行**阶段，**实现未开始**，全部未勾选。

## 功能验收

### US1 - 调整项真实进入净额计算（Priority: P1）

- [ ] 登记调整项（`CREDIT +500` / `DEBIT -300`，含非空 `reason`/`operator`/`idempotencyKey`）后建批，`adjustmentMinor = +200`、`netMinor = income − refund + 200`（SC-001 / FR-003~FR-005）
- [ ] 批次含 2 条 `ADJUSTMENT` 明细（reference = 调整项业务编号），带符号求和 = `adjustmentMinor`（INV-4）
- [ ] 同幂等键重复登记返回首次调整项，不产生第二条、金额不重复计入（FR-002 / SC-002）
- [ ] 同键不同参数（金额/方向/周期不同）被拒（`DUPLICATE`），未静默覆盖首次登记（FR-002）
- [ ] `(merchant, period)` 已存在批次时登记被拒（`STATE_TRANSITION_VIOLATION`），批次净额不变（FR-006）
- [ ] `reason`/`operator` 为空或空白被拒（`INVALID_ARGUMENT`），不落库（FR-002）
- [ ] 调整项币种与批次币种不一致被拒（`AMOUNT_INVARIANT_VIOLATION`），未静默混算（FR-022 / G3）
- [ ] 登记与拒绝分别写 `FINANCIAL_AUDIT` 并递增 `settlement.adjustment_registered` / `settlement.adjustment_rejected{reason=...}`（FR-016 / SC-008）
- [ ] 撤销（`revoke()`）后的调整项不参与后续计算（data-model §4）

### US2 - 「未确认事实不得结算」闸门本地可执行（Priority: P2）

- [ ] 事实 `type` 不属于 `{PAYMENT, REFUND}` ⇒ 拒绝建批（`INVALID_ARGUMENT`），未静默进明细（FR-007 / G2）
- [ ] 事实 `currencyCode` 与批次币种不一致 ⇒ 拒绝（`AMOUNT_INVARIANT_VIOLATION`），未静默相加（FR-022）
- [ ] 事实 `amountMinor < 0` ⇒ 拒绝（`AMOUNT_INVARIANT_VIOLATION`）（FR-007）
- [ ] `settlement-summary.period` 与请求 `period` 不一致 ⇒ 拒绝（`INVALID_ARGUMENT`），留含周期与 `traceId` 的 WARN（FR-007）
- [ ] 该周期无对账批次（404）⇒ 归一化为 `NOT_FOUND`，未冒泡为 Feign 异常（FR-009 / N2 / SC-004）
- [ ] 闸门拒绝时 `settlement_batches` 无记录（不落半成品），`settlement.gate_rejected{reason}` 递增（FR-008）
- [ ] 建批成功后明细与合计一致：`sum(PAYMENT) = income`、`sum(REFUND) = refund`、`sum(ADJUSTMENT 带符号) = adjustment`，且明细条数 = `fact_count` + 调整项条数（INV-4 / INV-5 / SC-003）
- [ ] settlement 对 reconciliation / payment / refund 零写路径（仅 `GET settlement-summary`）（FR-010 / INV-15）
- [ ] 幂等键命中但 `merchantId`/`period` 不一致 ⇒ `DUPLICATE`，未静默返回他商户/他周期批次（FR-012 / N5 / SC-005）

### US3 - 结果可查询、可收敛、可关闭，并记账（Priority: P3）

- [ ] 按 `merchantId` + `period` 查询命中批次并返回完整金额与状态；不存在返回 `NOT_FOUND`（FR-015）
- [ ] 收敛携带非空 `operator` + `reason`；为空被拒（`INVALID_ARGUMENT`）；审计含前后状态、操作人、理由、`traceId`（FR-016 / N6）
- [ ] `POST /internal/settlements/batches/{id}/close`：`SUCCEEDED`/`FAILED` → `CLOSED`，重复关闭幂等吸收；非终态关闭被拒（FR-014 / N3 / SC-006）
- [ ] 收敛为 `SUCCEEDED` 后向 `ledger-service` 提交平衡 Posting（DEBIT `MERCHANT_PAYABLE` / CREDIT `SETTLEMENT_PAYABLE`，幂等键 `SETTLEMENT:<batchId>`）（FR-017 / SC-007，依赖 ADR-0023 采纳）
- [ ] 重复收敛不产生第二条 Posting（幂等键唯一约束 + 先回查）（FR-017）
- [ ] 记账失败/超时**不回滚**批次状态，递增 `ledger.posting_failed`（`module=settlement`）并留下待记账痕迹（FR-017）
- [ ] `netMinor <= 0` 不发起记账，并记录跳过原因（FR-018）
- [ ] 收敛为 `FAILED` 时**无**该批次 Posting，且 `settlement.failed` 递增（FR-017）
- [ ] 全路径无任何真实出款/银行/渠道划出调用（FR-020 / INV-12）

## 非功能验收

- [ ] 金额全程 `long` 最小货币单位，金额路径 0 处 `float`/`double`（FR-004 / INV-1 / SC-010）
- [ ] 出站 RPC（merchant / reconciliation / ledger）显式超时（connect 1s / read 3s）；仅对幂等只读 GET 有限重试（≤ 3 次 / 1s-2s-4s）；写操作（记账 POST）0 重试（FR-019 / N4 / SC-009）
- [ ] 未引入 MQ / 2PC/XA / Resilience4j（FR-019）
- [ ] Database-per-service：仅读写 `settlement` Schema，0 处跨服务 SQL（FR-021）
- [ ] 状态迁移全部经 `SettlementBatch` 集中方法，0 处散落 `setStatus`；并发更新由乐观锁拦截（`CONFLICT`）（FR-013 / INV-10 / SC-006）
- [ ] `./mvnw verify` 全量通过；既有 5 个测试类用例未被删改（FR-023）

## 决策验收（Constitution §8）

- [ ] ADR-0022（调整项模型：方向语义 / 持久化 / 登记门禁 / 净额公式）经负责人确认并置 Accepted
- [ ] ADR-0023（闸门纵深防御 / settlement→ledger 记账归属与时机 / N1 归属 / 幂等键错配）经负责人确认并置 Accepted
- [ ] `settlement_adjustments` 新表与 `settlement_batches` 新增两列经确认（§8.3）
- [ ] 新增端点（`/adjustments`、`/{id}/close`、按商户+周期查询）与 FR-012 行为变更经确认（§8.4）
- [ ] `SettlementBatch.compute` 不变量与 `close()` 可达性变更经确认（§8.8）

## 验收结论

- **状态**：未完成（待 ADR 确认 + 实现）
- **阻塞**：ADR-0022~0023 待负责人决策；N1（事实无商户维度 ⇒ 跨商户串账）为最重要的遗留风险，需裁定是否另立契约变更 Feature
