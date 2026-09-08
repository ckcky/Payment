# Acceptance: Reconciliation 对账（缺口补齐）

**Feature**: `006-reconciliation` | **Date**: 2026-08-29 | **回检**: 2026-09-09 | **Spec**: [spec.md](spec.md)

> 本文件为 Feature 验收清单。2026-09-09 对照 SC-001~SC-008 / FR-001~FR-021 全量回检：**23/24 项通过**，
> 唯一未通过项为「乐观锁冲突（INV-10）」——需真库承载 `version` 列冲突，对应 tasks.md T023（保留未勾，不做假绿）。
> 证据来源：`reconciliation-service` 单测 **122 全绿**（本轮 68 → 122），逐项对应见下方「证据」列。

## 功能验收

### US1 - 按周期对账与差异识别

- [x] 不同周期使用各自账单 fixture，产出**不同**的匹配/差异集合（SC-001）—— 证据：`ReconciliationPeriodScenarioTest.periodFixturesYieldDifferentDifferenceSets`（`2026-08-31.csv` vs `2026-09-30.csv`）。**命名注记**：spec 原写月格式，代码先行为日期格式，语义等价
- [x] 无周期专属 fixture 时回退默认 fixture，批次记录 `statement_source` 且 `reconciliation.statement_fallback` 递增 + WARN 日志（SC-002 / FR-003）—— 证据：`CsvChannelStatementLoaderTest`、`ReconciliationStatementFallbackMetricsTest`
- [x] 同周期重复执行对账返回同一 `batchId`，不重复比对、不覆盖已处理差异（FR-001 / 回归）—— 证据：`ReconciliationApplicationServiceTest.duplicateRunWithSamePeriodReturnsSameBatchId`、`ReconciliationPeriodScenarioTest.rerunningSamePeriodReturnsTheStoredBatch`
- [x] 四类差异均可识别与查询，每条含双侧金额/状态/处理状态/依据（SC-003）—— 证据：`ReconciliationMatchingTest` + `ReconciliationNoFactMutationTest.differenceRecordsCarryBothSideSnapshotWithoutMutation`
- [x] 非法账单行与 null reference 有显式可观测痕迹，未被静默丢弃（FR-018）—— 证据：loader 非法行 WARN（含行号）；`ReconciliationMatching` null reference 逐条 WARN + 汇总计数（本轮补齐，T019）

### US2 - 差异处理与批次生命周期

- [x] 处理首条差异后批次由 `HAS_DIFFERENCE` 推进 `PROCESSING`，幂等（重复处理不报错）（FR-008）—— 证据：`ReconciliationLifecycleTest.firstResolveMovesBatchToProcessing`、`DifferenceResolveTest.repeatedResolveRefreshesIdempotently`
- [x] 存在未处理差异时关闭被拒（`UNRESOLVED_DIFFERENCES`），状态不变（FR-009 / INV-4）—— 证据：`ReconciliationLifecycleTest.closeIsRejectedWhileDifferencesRemain`
- [x] 全部差异处理后 `POST /internal/reconciliation/batches/{id}/close` 推进 `CLOSED`，`closed_at`/`closed_by` 落库（FR-009）—— 证据：`ReconciliationLifecycleTest.closeSucceedsAfterAllDifferencesResolved`；落库映射见 `ReconciliationBatchEntity` + `MybatisReconciliationRepository`
- [x] `CONSISTENT` 批次可直接关闭（无需经过 PROCESSING）（FR-009）—— 证据：`ReconciliationBatchStateMachineTest.consistentToClosed`
- [x] `CLOSED` 批次：处理差异被拒、重复关闭幂等吸收、`settlement-summary` 仍可读（FR-010 / INV-3）—— 证据：`ReconciliationLifecycleTest.resolvingDifferenceOnClosedBatchIsRejected` / `closingClosedBatchIsIdempotent`；summary 可读见 `ReconciliationBatchResponseTest`
- [x] `resolutionNote` 为空被拒（`INVALID_ARGUMENT`）；处理后 `resolvedAt`/`resolvedBy` 落值（FR-007 / INV-11）—— 证据：`DifferenceResolveTest`；API 边界另有 `@NotBlank` + `@Valid`（本轮补齐，T028）
- [x] `HAS_DIFFERENCE` 直接关闭被拒 —— 证据：`ReconciliationBatchStateMachineTest.closeDirectlyFromHasDifferenceIsIllegal`。**注**：实际抛 `UNRESOLVED_DIFFERENCES` 而非 `STATE_TRANSITION_VIOLATION`——门禁先查「尚有未处理差异」，失败原因更精确（`HAS_DIFFERENCE` 语义上必有未处理差异）

### US3 - 事实读取弹性

- [x] 事实读取 RPC 按配置超时（connect 1s / read 3s）生效（FR-012）—— 证据：`FeignFactsResilienceConfigTest`（本轮把硬编码改为 `services.payment.*` 配置绑定，T035）
- [x] 瞬时故障触发有限重试（≤ 3 次、1s/2s/4s），仅作用于只读幂等 GET（FR-013）—— 证据：`FactReadRetryTest`。**本轮修复缺陷**：`errorDecoder` 原先对所有状态码返回 `BizException`，而 Feign 只对 `RetryableException` 重试 ⇒ 重试器**永不生效**；现 408/429/5xx 抛 `RetryableException`，耗尽后由适配器归一化为 `INTERNAL_ERROR`
- [x] 读取失败**不入批**（该周期无批次），可被安全重跑（FR-014 / INV-8）—— 证据：`ReconciliationFactReadFailureTest`
- [x] 失败产出 `reconciliation.fact_read_failed`（含 `target` 维度）+ 含 `traceId` 的结构化日志（FR-014）—— 证据：同上（payment / refund 两个 target 各一例）

### US4 - 可观测与审计

- [x] 差异条数与**差异金额**均可观测（`reconciliation.difference_amount_minor`）（FR-011 / SC-007）—— 证据：`ReconciliationMetricsTest.recordsDifferenceAmountMinor`（本轮补齐，T040）
- [x] 差异处理与批次关闭各写一条 `FINANCIAL_AUDIT`（含 traceId/操作人/前后状态）（FR-011）—— 证据：`ReconciliationAuditTest`（logback `ListAppender` 直读 `FINANCIAL_AUDIT`）。**注**：审计记结构化字段，不回显处理说明正文（避免敏感明细入审计）
- [x] 批次响应暴露 `unresolvedDifferenceCount`，与 `settlement-summary` 同源同值（FR-017 / INV-12）—— 证据：`ReconciliationBatchResponseTest`。**本轮真补齐**：该字段此前在 tasks.md 中已勾选但代码里并不存在（勾选虚标，T010/T041）

## 非功能验收

- [x] 金额全程 `long` 分，对账金额路径无 `float`/`double`（FR-005 / SC-008）—— 证据：`differenceAmountMinor()` 与全部差异金额字段均为 `long`/`Long`
- [x] 状态迁移全部经 `ReconciliationBatch` 四方法，无散落 `setStatus`（FR-016）—— 证据：领域层仅 `start/finish/beginProcessing/close` 四个入口（`setStatementSource` 为溯源字段登记，非状态迁移）
- [ ] 乐观锁冲突（`CONFLICT`）在并发 resolve/close 下生效（INV-10）—— **未通过**：需真库（MySQL/Testcontainers）承载 `version` 列冲突；内存仓储无版本号语义。对应 tasks.md T023，列为后续项
- [x] 未引入 MQ / 2PC/XA / Resilience4j 等新中间件（FR-015）—— 证据：`FactsClientConfig` 局部 Feign 配置（Retryer + ErrorDecoder），无新中间件依赖
- [x] Database-per-service：仅读写 `reconciliation` Schema，无跨服务 SQL（FR-019）—— 证据：事实获取全部经 Feign（`PaymentFactsFeignClient` / `RefundFactsFeignClient`）
- [x] **原始 Payment/Refund 事实零回写**：对账与差异处理前后事实快照不变（SC-005 / INV-6）—— 证据：`ReconciliationNoFactMutationTest`
- [x] `./mvnw verify` 全量通过；`ReconciliationBatchStateMachineTest` 既有用例仍通过（FR-020）—— 证据：reconciliation-service **122 全绿**（状态机用例 9 → 13，既有 9 个保持通过）

## 决策验收（Constitution §8）

- [x] ADR-0019~0021 经负责人确认并更新状态为 Accepted —— `docs/adr/0007-reconciliation-decisions.md` 三份均为「✅ Accepted（2026-08-30 负责人裁决 accept；实现已落地）」
- [x] `reconciliation_batches` 新增三列经确认（§8.3）—— `deployment/schema/07-reconciliation-schema.sql:16-18`（非破坏性 NULL 列）
- [x] 新增 `POST .../batches/{id}/close` 端点经确认（§8.4，向后兼容新增）—— `ReconciliationController:53-57`，仅增端点不改既有契约
- [x] 批次状态机幂等扩展与关闭门禁经确认（§8.8）—— `beginProcessing`/`close` 幂等 + `UNRESOLVED_DIFFERENCES` 门禁
- [x] 文档状态漂移已修正：`technical-solution.md:105`、`roadmap.md`（FR-021 / G4）—— roadmap 已登记 `006-reconciliation（ADR-0019~0021 Accepted）`；本轮同步 spec/系统文档

## 验收结论

- **状态**：✅ **Implemented**（2026-09-09 回检；23/24 项通过）
- **唯一遗留**：INV-10 乐观锁并发（tasks.md T023）——需真库承载，不做假绿断言
- **阻塞**：无。N1（平台事实周期口径）与 N5（重复 reference）为设计期开放项，不影响本次验收（N5 已按「不静默丢弃」处理：null reference 显式 WARN，不改匹配语义）
