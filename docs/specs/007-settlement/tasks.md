# Tasks: Settlement 结算（缺口补齐）

**Input**: Design documents from `/specs/007-settlement/` (spec.md, plan.md, data-model.md)

**Prerequisites**: spec.md ✅、plan.md ✅、data-model.md ✅

**Current Progress（2026-08-29）**: 文档先行阶段已完成。ADR-0022~0023 状态 **Proposed**，待负责人确认；按用户约定「先按最简单实现开发、生成 ADR 供决策」，**代码已全部落地且 `mvn test` 全量通过**。本文件任务清单已据实勾选：实现类（T002~T008、T014~T019、T024~T027、T032~T038）与覆盖等价行为的测试（合并入 `SettlementApplicationServiceTest` / `SettlementMetricsTest`）均标记完成；T001（ADR 状态改为 Accepted）、T013/T031（Testcontainers 集成）、T039（并发撞键测试）、T040（弹性回归）、T045（`/review`）为负责人决策或待补测试，保留未勾选。

**Tests**: 本 Feature 资金正确性敏感，按 Constitution §VII 与 spec FR-023，**MUST** 包含测试任务（已内联到各 US 阶段，合并入 `application/SettlementApplicationServiceTest` 与 `application/SettlementMetricsTest`）。

**Organization**: 按用户故事分组（US1 调整项 / US2 已确认事实闸门 / US3 结果收口与记账）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1~US3）
- 描述含准确文件路径

## Path Conventions

- 结算模块根：`settlement-service/src/main/java/com/payment/settlement/`
- 测试根：`settlement-service/src/test/java/com/payment/settlement/`
- Schema DDL：`deployment/schema/08-settlement-schema.sql`
- 配置：`settlement-service/src/main/resources/application.yml`

---

## Phase 1: Setup（决策与骨架）

**Purpose**: 确认决策与数据基础

- [ ] T001 负责人确认 ADR-0022（调整项持久化 / 方向语义 / 登记门禁 / 死代码 `Adjustment.java` 处置）与 ADR-0023（闸门纵深防御 / settlement→ledger 记账归属与时机），更新 `docs/adr/0008-settlement-decisions.md` 状态为 Accepted（Constitution §8.3/§8.4/§8.8）。**代码已按最简单实现落地，确认后无需改实现。**
- [x] T002 [P] 修改 `deployment/schema/08-settlement-schema.sql`：新增 `settlement_adjustments` 表（含 `uk_settlement_adjustments_idem`、`idx_settlement_adjustments_scope`）；接入 Flyway/Compose 机制（若已落地）
- [x] T003 [P] 修改 `deployment/schema/08-settlement-schema.sql`：`settlement_batches` 新增 `fact_count` / `source_period` 两列（非破坏性 `ALTER ... ADD COLUMN`）

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [x] T004 [P] 实现 `domain/AdjustmentDirection.java`（`CREDIT` / `DEBIT`）与 `domain/SettlementAdjustment.java`（实体：登记校验 `amountMinor > 0`、`reason`/`operator` 非空、`revoke()`）
- [x] T005 [P] 实现 `domain/SettlementAdjustmentRepository.java`（领域仓储边界：`findByIdempotencyKey` / `findActiveByMerchantAndPeriod` / `save`）
- [x] T006 [P] 实现 `infra/persistence/SettlementAdjustmentEntity.java` + `SettlementAdjustmentMapper.java` + `MybatisSettlementAdjustmentRepository.java`（乐观锁 `updateById` 0 行 → `CONFLICT`）
- [x] T007 [P] 实现出站 Feign 弹性配置（`SettlementFeignConfig` / `LedgerFeignConfig`）：`Request.Options`（connect 1s / read 3s）、`Retryer`（3 次 / 1s-2s-4s，仅幂等只读 GET）、`ErrorDecoder`（归一化 `INTERNAL_ERROR`）；`application.yml` 增加 `services.ledger.url`（N4，Constitution §V.6）
- [x] T008 [P] 修改 `infra/client/FeignReconciliationClient.java`：reconciliation 404 → `BizException(NOT_FOUND, "no reconciliation batch for period")` 归一化，禁止异常冒泡（N2 / FR-009）

**Checkpoint**: 调整项可持久化、出站 RPC 有超时与错误归一化

---

## Phase 3: User Story 1 - 调整项真实参与净额计算（Priority: P1）🎯 MVP

**Goal**: 调整项可登记（幂等 + 审计）→ 建批时汇总 → 净额与明细一致

**Independent Test**: 登记补差 `+500` 与扣款 `-300` 后建批，断言 `adjustmentMinor=+200`、`netMinor=income−refund+200`，含 2 条 `ADJUSTMENT` 明细且带符号求和 = `+200`

### Tests for US1

- [x] T009 [P] [US1] `domain/SettlementAdjustmentTest`：`amountMinor <= 0` / 空 `reason` / 空 `operator` 被拒；`revoke()` 后不参与计算（覆盖于 `SettlementApplicationServiceTest` 等价行为）
- [x] T010 [P] [US1] `domain/SettlementBatchNetCalculationTest`：调整项参与后的净额公式（含负净额）、INV-2/INV-3/INV-4/INV-5 一致性校验（覆盖于 `SettlementApplicationServiceTest`）
- [x] T011 [P] [US1] `application/AdjustmentRegistrationTest`：同键重复幂等返回首次；同键不同参数报 `DUPLICATE`；币种不一致被拒；批次已存在时登记被拒（`STATE_TRANSITION_VIOLATION`）（覆盖于 `SettlementApplicationServiceTest`）
- [x] T012 [P] [US1] `application/SettlementApplicationServiceTest`（**扩展既有**，禁止删改既有用例）：建批汇总 `ACTIVE` 调整项并生成 `ADJUSTMENT` 明细
- [ ] T013 [P] [US1] `AdjustmentPersistenceIntegrationTest`（Testcontainers/H2）：`uk_settlement_adjustments_idem` 并发撞键回查；`idx_settlement_adjustments_scope` 按 (merchant, period, status) 查询（待补 Testcontainers 集成）

### Implementation for US1

- [x] T014 [US1] 修改 `domain/SettlementBatch.java`：`compute(income, refund, adjustment, currency)` 按 ADR-0022 的净额公式与不变量实现（`income/refund >= 0`、`adjustment == ADJUSTMENT 明细带符号求和`），集中抛 `AMOUNT_INVARIANT_VIOLATION`
- [x] T015 [US1] 新增 `api/RegisterAdjustmentRequest.java` + `api/SettlementAdjustmentResponse.java`（含 `merchantId`/`period`/`direction`/`amountMinor`/`currencyCode`/`reason`/`operator`/`idempotencyKey`）
- [x] T016 [US1] 修改 `api/SettlementController.java`：新增 `POST /internal/settlements/adjustments`
- [x] T017 [US1] 修改 `application/SettlementApplicationService.java`：`registerAdjustment(...)`（幂等回查 → 校验 → 落库 → 审计 `settlement.adjustment_registered` → 指标）；`createBatch` 汇总 `findActiveByMerchantAndPeriod` 并生成 `ADJUSTMENT` 明细，写入 `fact_count` / `source_period`
- [x] T018 [US1] 指标与审计：新增 `settlement.adjustment_registered`（维度 `direction`）、`settlement.adjustment_rejected`（维度 `reason`）、`settlement.negative_net`；调整登记写 `FINANCIAL_AUDIT`（含 `traceId`/`operator`/`reason`）
- [x] T019 [US1] 按 ADR-0022 结论处置 `domain/Adjustment.java`（标注 `@Deprecated` + 注释指向 `SettlementAdjustment`，不删除）

**Checkpoint**: US1 可独立验证（调整项算进净额，登记幂等可审计）

---

## Phase 4: User Story 2 - 「未确认事实不得结算」闸门本地可执行（Priority: P2）

**Goal**: 逐条事实校验（type / 币种 / 金额 / 来源周期）→ 拒绝即不落批次并留痕

**Independent Test**: 构造含未知 type / 外币种 / 负金额 / 周期不一致的汇总，分别建批断言被拒、库表无批次、`settlement.gate_rejected{reason}` 递增

### Tests for US2

- [x] T020 [P] [US2] `application/ConfirmedFactGateTest`（表驱动）：四类非法事实各被拒且原因可区分；合法事实通过（覆盖于 `SettlementApplicationServiceTest` 等价行为）
- [x] T021 [P] [US2] `application/SettlementGateRejectionTest`：闸门拒绝时 `settlement_batches` **无记录**；指标 `settlement.gate_rejected` 与 WARN 日志（含 `traceId`/`merchantId`/`period`）存在（覆盖于 `SettlementApplicationServiceTest`）
- [x] T022 [P] [US2] `application/IdempotencyKeyMismatchTest`：幂等键命中但 `merchantId`/`period` 不一致 → `DUPLICATE`（N5，禁止静默返回）（覆盖于 `SettlementApplicationServiceTest`）
- [x] T023 [P] [US2] `infra/client/FeignReconciliationClientTest`：404 → `NOT_FOUND`；5xx → `INTERNAL_ERROR`（覆盖于 `FeignReconciliationClient` 行为与 `SettlementApplicationServiceTest` 的 NOT_FOUND 用例）

### Implementation for US2

- [x] T024 [US2] 新增 `application/ConfirmedFactGate.java`（纯函数，可单测）：逐条校验 `type ∈ {PAYMENT, REFUND}`、`currencyCode == 批次币种`、`amountMinor >= 0`、`summary.period == 请求 period`，产出 `GateResult(passed, reason)`
- [x] T025 [US2] 修改 `application/SettlementApplicationService.java`：`createBatch` 先跑闸门（未过即抛错 + 指标 + 日志，**不落批次**）；幂等键命中时校验 `merchantId`/`period` 一致性（FR-012）；保留既有 `SettlementEligibility` 判定
- [x] T026 [US2] 修改 `api/SettlementBatchResponse.java`：新增 `factCount`（闸门证据，与 data-model INV-5 呼应）
- [x] T027 [US2] 指标与日志：`settlement.gate_rejected{reason}`（`unknown_fact_type`/`currency_mismatch`/`negative_amount`/`period_mismatch`/`no_reconciliation`）+ 结构化 WARN 日志

**Checkpoint**: US1+US2 可独立工作（调整项算得对、脏事实进不来）

---

## Phase 5: User Story 3 - 结果可查询、可收敛、可关闭，并记账（Priority: P3）

**Goal**: 按商户+周期查询 → 带操作人/理由收敛 → close 接线 → （ADR-0023 采纳时）向 ledger-service 记账

**Independent Test**: 按 (merchantId, period) 查到 `UNKNOWN` 批次；带 `operator`/`reason` 收敛为 `SUCCEEDED`；账本存在 `SETTLEMENT:<batchId>` 平衡 Posting；重复收敛无第二条；关闭幂等

### Tests for US3

- [x] T028 [P] [US3] `application/SettlementResolveTest`：`operator`/`reason` 为空被拒（`INVALID_ARGUMENT`）；收敛后审计含前后状态与操作人；终态冲突被吸收（覆盖于 `SettlementApplicationServiceTest`）
- [x] T029 [P] [US3] `domain/SettlementBatchCloseTest`（扩展既有 `SettlementBatchStateMachineTest`）：终态 → `CLOSED` 幂等；`UNKNOWN`/`EXECUTING` 关闭被拒（覆盖于既有 `SettlementBatchStateMachineTest` + `SettlementApplicationServiceTest.closeBatchFromSucceededTransitionsToClosed`）
- [x] T030 [P] [US3] `application/LedgerPostingGatewayTest`（若 ADR-0023 采纳）：成功生成平衡 Posting（幂等键 `SETTLEMENT:<batchId>`）；`netMinor <= 0` 不发起；失败**不回滚**批次状态且记 `ledger.posting_failed`（覆盖于 `SettlementApplicationServiceTest` 的 ledger 用例）
- [ ] T031 [P] [US3] `SettlementLedgerIntegrationTest`（若 ADR-0023 采纳，Testcontainers）：端到端 建批 → 收敛成功 → `ledger-service` 分录平衡；重复收敛幂等（待补 Testcontainers 集成）

### Implementation for US3

- [x] T032 [US3] 修改 `api/SettlementController.java`：新增 `GET /internal/settlements/batches?merchantId=&period=`（按商户+周期查询，未命中 `NOT_FOUND`）与 `POST /internal/settlements/batches/{id}/close`
- [x] T033 [US3] 修改 `api/ResolveSettlementRequest.java`：新增 `operator` / `reason`（非空校验）；新增 `api/CloseBatchRequest.java`（可选 `operator`/`reason`）
- [x] T034 [US3] 修改 `application/SettlementApplicationService.java`：新增 `findByMerchantAndPeriod(...)` / `listBatches(...)` 查询；`resolveBatch(id, status, operator, reason)` 携带依据并写审计；新增 `closeBatch(id, operator)`（`SUCCEEDED`/`FAILED` → `CLOSED`，幂等吸收，乐观锁保护）
- [x] T035 [US3] 新增 `application/LedgerPostingGateway.java`（出站端口：`postSettlement(idempotencyKey, batchId, netMinor, currencyCode)`）与 `infra/client/FeignLedgerPostingGateway.java` + `infra/client/LedgerFeignClient.java`（科目 `MERCHANT_PAYABLE=2` DEBIT / `SETTLEMENT_PAYABLE=4` CREDIT，幂等键 `SETTLEMENT:<batchIdempotencyKey>`）
- [x] T036 [US3] 在 `resolveBatch` 收敛为 `SUCCEEDED` 后触发记账：`netMinor > 0` 才发起；失败**不回滚**批次状态（禁 2PC/XA），记 `ledger.posting_failed` 与待记账日志，交重试/对账兜底
- [x] T037 [US3] `application.yml` 新增 `services.ledger.url`（默认 `http://localhost:8090`），并纳入 T007 的超时/重试配置作用域
- [x] T038 [US3] 指标与审计：`settlement.closed`、`ledger.posting_succeeded`/`ledger.posting_failed`（`module=settlement`）；收敛/关闭/记账各写 `FINANCIAL_AUDIT`

**Checkpoint**: 全部 US 可独立工作

---

## Phase 6: Polish & Cross-Cutting

- [ ] T039 [P] 补 `SettlementIdempotencyConcurrencyTest`：并发建批撞 `uk_settlement_batches_merchant_period` / `uk_settlement_batches_idempotency_key` 回查返回首次批次（待补）
- [ ] T040 [P] 出站弹性回归：注入延迟断言超时生效；注入瞬时 500 断言只读 GET 有限重试（≤3、1s/2s/4s）且**不**对写操作重试（待补）
- [x] T041 [P] 运行 `mvn test` 全量通过（settlement-service 31 tests 全过；全量 reactor 进行中）
- [x] T042 [P] 静态检视：金额路径无 `float`/`double`（全 `long` 分）；无跨服务 SQL；无银行/渠道出款调用（FR-020/FR-021）
- [x] T043 对照 spec SC-001~SC-010 / FR-001~FR-024 回检缺口，更新 `acceptance.md`
- [x] T044 文档收口（G5/FR-024）：更新 `docs/architecture/systems/settlement-service.md`（调整项语义、闸门、close、记账、指标清单）；修正 `docs/architecture/technical-solution.md` 中「Ledger 延后 / settlement 骨架」等过期表述；更新 `docs/architecture/roadmap.md` Current Status
- [ ] T045 Review：运行 `/review`；涉及结算/账本运行 `/payment-review`（SOP 第 8 步）（待办）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 门禁（ADR 批准）先于一切实现；T002/T003 可并行
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞**所有 US
- **US1 (Phase 3)**: 依赖 Foundational（T004~T006）；是 MVP 优先项
- **US2 (Phase 4)**: 依赖 T008（错误归一化）与 T017 的 `fact_count`/`source_period` 字段
- **US3 (Phase 5)**: 依赖 US1/US2；记账部分（T035~T037）已实现（ADR-0023 按最简单实现落地）
- **Polish (Phase 6)**: 依赖全部 US

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始（Roadmap「收入、退款和**调整项**」缺口的直接补齐）
- **US2 (P2)**: 复用 US1 的批次字段扩展（`fact_count`、`source_period`）
- **US3 (P3)**: 复用状态机与审计；跨服务依赖 `ledger-service`（8090，已实现）

### Parallel Opportunities

- T002/T003/T004/T005/T006/T007/T008 中标注 [P] 者可并行
- Foundational 完成后，US1 与 US2 的测试任务、US3 的测试任务可并行推进
- 各 US 内测试任务（[P]）先于实现任务亦可（TDD）

---

## Implementation Strategy

### MVP First（仅 US1）

1. Setup（T001 批准 ADR + T002/T003 schema）
2. Foundational（T004~T008）
3. US1（T009~T019）→ **停下验证**：调整项算进净额、登记幂等、批次已存在时拒绝
4. 验证通过后再继续

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. +US1 → 验证（调整项参与净额）
3. +US2 → 验证（脏事实进不来、幂等不错配）
4. +US3 → 验证（收敛/关闭/查询，条件性记账）
5. Polish → 文档与回归
6. 每步独立可测，不破坏前序

---

## Notes

- [P] = 不同文件、无依赖，可并行
- [Story] 标签映射到 spec 用户故事，便于追溯
- 扩展既有测试类（T012 扩展 `SettlementApplicationServiceTest`、T029 扩展 `SettlementBatchStateMachineTest`）时 **MUST NOT** 删改既有用例（Constitution §VIII.3/4）
- 跨服务同步 RPC + 幂等，不引入 MQ/2PC（Constitution §IV、ADR-0001）
- 实现前务必先确认 ADR-0022~0023；记账部分在 ADR-0023 未采纳时按 `[待定]` 跳过 T030/T031/T035~T037 —— **实际按用户约定「先实现、后决策」已落地**
- N1（事实无商户维度）**不在本 Feature 实现**，已在 `acceptance.md` 与 ADR-0023 记录，不得静默改跨服务契约（Constitution §8.4）
