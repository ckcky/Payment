# Acceptance: Reconciliation 对账（缺口补齐）

**Feature**: `006-reconciliation` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

> 本文件为 Feature 验收清单。实现阶段完成后逐项勾选。当前（2026-08-29）处于**文档先行**阶段，**实现未开始**，全部未勾选。

## 功能验收

### US1 - 按周期对账与差异识别

- [ ] 不同周期（`2026-08` / `2026-09`）使用各自账单 fixture，产出**不同**的匹配/差异集合（SC-001）
- [ ] 无周期专属 fixture 时回退默认 fixture，批次记录 `statement_source` 且 `reconciliation.statement_fallback` 递增 + WARN 日志（SC-002 / FR-003）
- [ ] 同周期重复执行对账返回同一 `batchId`，不重复比对、不覆盖已处理差异（FR-001 / 回归）
- [ ] 四类差异（AMOUNT_MISMATCH / STATUS_MISMATCH / PLATFORM_ONLY / CHANNEL_ONLY）均可识别与查询，每条含双侧金额/状态/处理状态/依据（SC-003）
- [ ] 非法账单行与 null reference 有显式可观测痕迹，未被静默丢弃（FR-018）

### US2 - 差异处理与批次生命周期

- [ ] 处理首条差异后批次由 `HAS_DIFFERENCE` 推进 `PROCESSING`，幂等（重复处理不报错）（FR-008）
- [ ] 存在未处理差异时关闭被拒（`UNRESOLVED_DIFFERENCES`），状态不变（FR-009 / INV-4）
- [ ] 全部差异处理后 `POST /internal/reconciliation/batches/{id}/close` 推进 `CLOSED`，`closed_at`/`closed_by` 落库（FR-009）
- [ ] `CONSISTENT` 批次可直接关闭（无需经过 PROCESSING）（FR-009）
- [ ] `CLOSED` 批次：处理差异被拒（`STATE_TRANSITION_VIOLATION`）、重复关闭幂等吸收、`settlement-summary` 仍可读（FR-010 / INV-3）
- [ ] `resolutionNote` 为空被拒（`INVALID_ARGUMENT`）；处理后 `resolvedAt`/`resolvedBy` 落值（FR-007 / INV-11）
- [ ] `HAS_DIFFERENCE` 直接关闭被拒（`STATE_TRANSITION_VIOLATION`）

### US3 - 事实读取弹性

- [ ] 事实读取 RPC 按配置超时（connect 1s / read 3s）生效（FR-012）
- [ ] 瞬时故障触发有限重试（≤ 3 次、1s/2s/4s），仅作用于只读幂等 GET（FR-013）
- [ ] 读取失败**不入批**（该周期无批次），可被安全重跑（FR-014 / INV-8）
- [ ] 失败产出 `reconciliation.fact_read_failed`（含 `target` 维度）+ 含 `traceId` 的结构化日志（FR-014）

### US4 - 可观测与审计

- [ ] 差异条数与**差异金额**均可观测（`reconciliation.difference_amount_minor`）（FR-011 / SC-007）
- [ ] 差异处理与批次关闭各写一条 `FINANCIAL_AUDIT`（含 traceId/操作人/前后状态/依据）（FR-011）
- [ ] 批次响应暴露 `unresolvedDifferenceCount`，与 `settlement-summary` 同源同值（FR-017 / INV-12）

## 非功能验收

- [ ] 金额全程 `long` 分，对账金额路径无 `float`/`double`（FR-005 / SC-008）
- [ ] 状态迁移全部经 `ReconciliationBatch` 四方法，无散落 `setStatus`（FR-016）
- [ ] 乐观锁冲突（`CONFLICT`）在并发 resolve/close 下生效（INV-10）
- [ ] 未引入 MQ / 2PC/XA / Resilience4j 等新中间件（FR-015）
- [ ] Database-per-service：仅读写 `reconciliation` Schema，无跨服务 SQL（FR-019）
- [ ] **原始 Payment/Refund 事实零回写**：对账与差异处理前后事实快照不变（SC-005 / INV-6）
- [ ] `./mvnw verify` 全量通过；`ReconciliationBatchStateMachineTest` 既有 6 个用例仍通过（FR-020）

## 决策验收（Constitution §8）

- [ ] ADR-0019~0021 经负责人确认并更新状态为 Accepted
- [ ] `reconciliation_batches` 新增三列经确认（§8.3）
- [ ] 新增 `POST .../batches/{id}/close` 端点经确认（§8.4，向后兼容新增）
- [ ] 批次状态机幂等扩展与关闭门禁经确认（§8.8）
- [ ] 文档状态漂移已修正：`technical-solution.md:105`、`roadmap.md`（FR-021 / G4）

## 验收结论

- **状态**：未完成（待 ADR 确认 + 实现）
- **阻塞**：ADR-0019~0021 待负责人决策；N1（平台事实周期口径）与 N5（重复 reference）待裁定归属
