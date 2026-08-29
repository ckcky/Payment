# Tasks: Reconciliation 对账（缺口补齐）

**Input**: Design documents from `/specs/006-reconciliation/` (spec.md, plan.md, data-model.md, acceptance.md, quickstart.md)

**Prerequisites**: spec.md ✅、plan.md ✅、data-model.md ✅、checklists/ ✅、acceptance.md ✅、quickstart.md ✅

**Current Progress（2026-08-29）**: 文档先行阶段已完成。**实现状态：全部未开始**。ADR-0019~0021 状态 **Proposed**，待负责人确认（Constitution §8.3/§8.4/§8.8）。

**Tests**: 本 Feature 涉及金额判定与状态机门禁，按 Constitution §VII 与 spec FR-020，**MUST** 包含测试任务（已内联到各 US 阶段）；**MUST NOT** 删测试或改测试迎合错误实现。

**Organization**: 按用户故事分组（US1~US4）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1~US4）
- 描述含准确文件路径

## Path Conventions

- 对账模块根：`reconciliation-service/src/main/java/com/payment/reconciliation/`
- 测试根：`reconciliation-service/src/test/java/com/payment/reconciliation/`
- 账单 fixture：`reconciliation-service/src/main/resources/fixtures/channel-statements/`
- Schema DDL：`deployment/schema/07-reconciliation-schema.sql`
- 配置：`reconciliation-service/src/main/resources/application.yml`
- ADR：`docs/adr/0007-reconciliation-decisions.md`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 确认决策与 schema 基线

- [ ] T001 负责人确认 ADR-0019~0021（`docs/adr/0007-reconciliation-decisions.md`），更新状态为 Accepted —— **实现门禁（Constitution §8.3/§8.4/§8.8）**
- [ ] T002 [P] 修改 `deployment/schema/07-reconciliation-schema.sql`：新增 `statement_source VARCHAR(255) NULL`、`closed_at DATETIME NULL`、`closed_by VARCHAR(64) NULL` 三列（data-model.md §2 DDL，非破坏性）
- [ ] T003 [P] 新增账单 fixture：`fixtures/channel-statements/2026-08.csv` 与 `2026-09.csv`（内容不同，用于验证按周期区分；保留 `sample.csv` 作为回退默认）
- [ ] T004 [P] 新增错误码 `UNRESOLVED_DIFFERENCES` 到 `common/common-core/.../error/ErrorCodes.java`（data-model.md §8）

---

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [ ] T005 修改 `domain/ReconciliationBatch.java`：`beginProcessing()` 幂等（`HAS_DIFFERENCE`/`PROCESSING` → `PROCESSING`，`:66`）；`close()` 幂等（`CLOSED` → `CLOSED` 空操作，`:72`）；`close()` 增加未处理差异门禁（≠0 抛 `UNRESOLVED_DIFFERENCES`）
- [ ] T006 [P] 修改 `domain/ReconciliationBatch.java`：新增 `statementSource`（`ChannelStatementSource`）、`closedAt`、`closedBy` 字段 + `rehydrate` 参数同步（`:35`）；新增 `unresolvedCount()` 与 `differenceAmountMinor()` 领域计算方法（data-model.md §2.2）
- [ ] T007 [P] 新增 `domain/ChannelStatementSource.java` 值对象（`sourceType`/`locator`/`entryCount`/`fallbackUsed`，data-model.md §5）
- [ ] T008 [P] 修改 `infra/persistence/ReconciliationBatchEntity.java` + `MybatisReconciliationRepository.java`：`statement_source`/`closed_at`/`closed_by` 映射与 `toDomain`/`toEntity` 同步（`:84-100`）
- [ ] T009 [P] 修改 `domain/Difference.java`：`resolve(note, actor, at)`（`:56`）—— 校验 `note` 非空白（`INVALID_ARGUMENT`），写入 `resolvedAt`/`resolvedBy`；已 `RESOLVED` 重复处理按 ADR-0019 决策（幂等刷新 or 拒绝）
- [ ] T010 [P] 修改 `api/ReconciliationBatchResponse.java`：新增 `unresolvedDifferenceCount`、`statementSource`、`closedAt`（向后兼容，仅增字段，data-model.md §2.2 / FR-017）

**Checkpoint**: 领域与持久层可承载生命周期闭合、账单来源与处理依据；既有状态机测试仍全绿

---

## Phase 3: User Story 1 - 按周期对账，差异可重复识别、可查询、可解释（Priority: P1）🎯 MVP

**Goal**: `load(period)` 真正按周期定位账单；回退留痕；差异可查询且含双侧金额/状态（缺口 G2 + N1 口径 + N4）

**Independent Test**: `2026-08` 与 `2026-09` 使用不同 fixture 得到不同结果；同周期重跑返回同一 `batchId`；查询差异含双侧金额/状态

### Tests for US1

- [ ] T011 [P] [US1] `infra/CsvChannelStatementLoaderTest`：命中周期 fixture（`fallbackUsed=false`）；未命中回退 `sample.csv` 且 `fallbackUsed=true`；两者皆无 → `INTERNAL_ERROR`；`period` 含 `/` 或 `..` → `INVALID_ARGUMENT`（路径穿越防护）
- [ ] T012 [P] [US1] `application/ReconciliationApplicationServiceTest`（扩展）：不同周期产出**不同**差异集合；同周期重跑返回同一 `batchId`（已实现回归）
- [ ] T013 [P] [US1] `application/ReconciliationStatementFallbackMetricsTest`：回退时 `reconciliation.statement_fallback` 递增且 WARN 日志含 period/locator
- [ ] T014 [P] [US1] `integration/ReconciliationPeriodScenarioTest`（Testcontainers/H2）：周期 A/B 各自对账 → 落库批次结果不同；批次行 `statement_source` 正确

### Implementation for US1

- [ ] T015 [US1] 修改 `infra/CsvChannelStatementLoader.java`：按 `period` 定位 `{fixture-dir}/{period}.csv`，未命中回退 `{fixture-dir}/sample.csv`；构造并返回 `ChannelStatementSource`（`:27`）
- [ ] T016 [US1] 修改 `infra/CsvChannelStatementLoader.java`：非法行（列数 < 4 / 金额非数字）显式 WARN + 行号，**不静默跳过**（FR-018）
- [ ] T017 [US1] 修改 `application/ReconciliationApplicationService.java:68-75`：把 `ChannelStatementSource` 写入批次；回退时递增 `reconciliation.statement_fallback` + WARN
- [ ] T018 [US1] 修改 `reconciliation-service/src/main/resources/application.yml`：新增 `reconciliation.statement.fixture-dir`（默认 `fixtures/channel-statements/`）与 `reconciliation.statement.default-file`（默认 `sample.csv`）
- [ ] T019 [US1] 修改 `domain/PlatformFact` 相关链路：平台事实 `reference` 为 null 时计数/WARN（`ReconciliationMatching.java:54-72` 的静默跳过），**不改匹配语义**（FR-018，N5 记 [待定]）

**Checkpoint**: US1 可独立验证（按周期对账成立）

---

## Phase 4: User Story 2 - 差异处理推进生命周期，全部处理后可关闭（Priority: P1）

**Goal**: 接线 `beginProcessing()`/`close()`，新增关闭端点与门禁，`CLOSED` 只读（缺口 G1 + N3）

**Independent Test**: 2 条差异的批次：处理 1 条 → `PROCESSING`；此时关闭被拒；处理第 2 条 → 关闭成功 `CLOSED`；关闭后再处理被拒、重复关闭幂等

### Tests for US2

- [ ] T020 [P] [US2] `domain/ReconciliationBatchStateMachineTest`（扩展）：`beginProcessing` 幂等（`PROCESSING` 再调用不抛）；`close` 幂等（`CLOSED` 再调用不抛）；有未处理差异 `close` → `UNRESOLVED_DIFFERENCES`；`HAS_DIFFERENCE` 直接 `close` → `STATE_TRANSITION_VIOLATION`；**既有 6 个用例保持通过**
- [ ] T021 [P] [US2] `domain/DifferenceResolveTest`：`resolutionNote` 空白 → `INVALID_ARGUMENT`；处理后 `resolvedAt`/`resolvedBy` 落值；重复处理按 ADR-0019 语义
- [ ] T022 [P] [US2] `application/ReconciliationLifecycleTest`：首条差异处理 → `PROCESSING`；未处理完关闭被拒；全部处理 → `CLOSED`；`CLOSED` 后处理差异被拒、重复关闭幂等
- [ ] T023 [P] [US2] `integration/ReconciliationLifecyclePersistenceTest`（Testcontainers/H2）：关闭后 `closed_at`/`closed_by` 落库；乐观锁冲突（并发 resolve + close）→ `CONFLICT`
- [ ] T024 [P] [US2] `integration/ReconciliationNoFactMutationTest`：对账与差异处理前后，payment/refund 侧事实快照不变（SC-005，`INV-6`）

### Implementation for US2

- [ ] T025 [US2] 修改 `application/ReconciliationApplicationService.java:104-115`（`resolveDifference`）：调用 `Difference.resolve(note, actor, at)` 后调用 `batch.beginProcessing()`，再 `repository.save(batch)`；`CLOSED` 批次拒绝处理
- [ ] T026 [US2] 新增 `application/ReconciliationApplicationService.closeBatch(batchId, operator)`：`requireBatch` → `batch.close()`（含未处理差异门禁）→ `save` → 写 `FINANCIAL_AUDIT` + 递增 `reconciliation.batch_closed`
- [ ] T027 [US2] 修改 `api/ReconciliationController.java`：新增 `POST /internal/reconciliation/batches/{id}/close`（`:46` 附近）；操作人取自请求头/请求体（`operator`，内部可信网络，鉴权沿用既有基线）
- [ ] T028 [US2] 修改 `api/ResolveDifferenceRequest.java`：新增可选 `operator` 字段（向后兼容）；`resolutionNote` 加 `@NotBlank`（`spring-boot-starter-validation` 已在依赖中）
- [ ] T029 [P] [US2] 接入 `StructuredAuditLogger`：差异处理与批次关闭各写一条 `FINANCIAL_AUDIT`（`traceId`/period/batchId/reference/前后状态/operator/note）

**Checkpoint**: US1+US2 可独立工作（差异可处理、批次可关闭）

---

## Phase 5: User Story 3 - 事实读取 RPC 超时、有限重试与失败可观测（Priority: P2）

**Goal**: 显式超时 + 仅对幂等 GET 的有限重试 + 失败不入批且可诊断（缺口 G3）

**Independent Test**: 注入 5s 延迟 → 读超时内失败且无批次落库；注入一次瞬时 500 → 重试后成功；持续失败 → `reconciliation.fact_read_failed` 递增

### Tests for US3

- [ ] T030 [P] [US3] `infra/client/FactReadRetryTest`（MockWebServer / WireMock 或 Feign stub）：瞬时 500 → 重试 3 次后成功；持续 500 → 耗尽后失败并递增 `reconciliation.fact_read_failed`
- [ ] T031 [P] [US3] `application/ReconciliationFactReadFailureTest`：读取失败时 **未落库**（`findByPeriod` 仍为空），周期可被安全重跑
- [ ] T032 [P] [US3] `infra/config/FeignFactsResilienceConfigTest`：超时属性绑定生效（connect 1s / read 3s）；ErrorDecoder 归一化为 `INTERNAL_ERROR`

### Implementation for US3

- [ ] T033 [US3] 新增 `infra/config/FeignFactsResilienceConfig.java`：`@Configuration` 提供 `feign.Retryer`（3 次、退避 1s/2s/4s）与 `ErrorDecoder`（归一化 + 埋点），**仅**作用于 payment/refund facts 客户端（`feign.Request.Options` 按 `@FeignClient` 的 `configuration` 局部绑定，避免全局污染）
- [ ] T034 [US3] 修改 `infra/client/PaymentFactsFeignClient.java:11` 与 `RefundFactsFeignClient.java:11`：绑定局部 configuration（不改 URL/契约）
- [ ] T035 [US3] 修改 `application.yml`：新增 `services.payment.connect-timeout-ms=1000` / `read-timeout-ms=3000`（refund 同），供配置类绑定
- [ ] T036 [US3] 修改 `application/ReconciliationApplicationService.java:66-67`：事实读取失败时递增 `reconciliation.fact_read_failed`（`target=payment|refund`）+ 结构化日志（含 `traceId`、`period`），异常上抛且**不落批**（INV-8）

**Checkpoint**: US1~US3 可独立工作（失败可诊断、不产生半成品批次）

---

## Phase 6: User Story 4 - 可观测与审计收口（Priority: P3）

**Goal**: 差异金额可观测；处理/关闭有审计；批次响应暴露 `unresolvedDifferenceCount`（N2 / N4）

**Independent Test**: 含金额差异的批次 → `reconciliation.difference_amount_minor` 有值；处理/关闭 → 两条 `FINANCIAL_AUDIT`；批次响应含 `unresolvedDifferenceCount`

### Tests for US4

- [ ] T037 [P] [US4] `application/ReconciliationMetricsTest`（扩展）：新增 4 个指标计数与 `difference_amount_minor` 金额口径（单侧缺失取该侧金额）
- [ ] T038 [P] [US4] `application/ReconciliationAuditTest`：`FINANCIAL_AUDIT` 含 `traceId`/operator/前后状态；敏感字段不落审计
- [ ] T039 [P] [US4] `api/ReconciliationBatchResponseTest`：`unresolvedDifferenceCount` 与 `settlementSummary` 同源同值（INV-12）

### Implementation for US4

- [ ] T040 [US4] 修改 `application/ReconciliationApplicationService.java:77-81`：增加 `reconciliation.difference_amount_minor`（按差异金额求和，保持 `long`）
- [ ] T041 [US4] 修改 `api/ReconciliationBatchResponse.from(...)`：输出 `unresolvedDifferenceCount`（复用 `batch.unresolvedCount()`，与 `settlementSummary` 同源，`:117-132`）

**Checkpoint**: 全部 US 可独立工作

---

## Phase 7: Polish & Cross-Cutting

- [ ] T042 [P] 运行 `./mvnw verify` 全量通过（reconciliation-service 及其下游 settlement-service 受影响）
- [ ] T043 [P] 按 `quickstart.md` 跑本地手动 e2e（按周期对账 / 回退留痕 / 差异处理 / 关闭门禁 / 重试不入批 / 审计）
- [ ] T044 [P] 对照 spec SC-001~SC-008 / FR-001~FR-021 回检缺口，更新 `acceptance.md`
- [ ] T045 [P] 更新 `docs/architecture/systems/reconciliation-service.md`：§2.2 状态机补「已接线」标注（删除 `:75` 的「未接线」诚实标注）、§2.3 新增三列、§3 新增 close 端点、§4.3 账单按周期、§5.4 超时/重试阈值、§6.4 新增指标
- [ ] T046 [P] 修正文档状态漂移（缺口 G4）：更新 `technical-solution.md:105`（reconciliation-service「骨架」→「已实现（差异处理生命周期补齐中/已完成）」）与 `roadmap.md` Current Status/Feature 状态
- [ ] T047 [P] 在 `docs/adr/README.md` 索引中登记 `0007-reconciliation-decisions.md`（ADR-0019~0021）
- [ ] T048 Review：运行 `/review`；涉及对账/资金路径运行 `/payment-review`（SOP 第 8 步）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 为门禁（ADR 批准）先于一切实现；T002~T004 可并行
- **Foundational (Phase 2)**: 依赖 Setup（schema 已改）；**阻塞**所有 US
- **US1 (Phase 3)**: Foundational 后开始（MVP，缺口 G2）
- **US2 (Phase 4)**: 依赖 Foundational 的 `beginProcessing`/`close` 改造（T005）；与 US1 可并行（不同文件）
- **US3 (Phase 5)**: 依赖 Foundational（T010 的响应字段非必须），与 US1/US2 松耦合
- **US4 (Phase 6)**: 依赖 US2 的处理/关闭动作（审计埋点）与 US1 的差异金额
- **Polish (Phase 7)**: 依赖全部 US

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始；为 US4 提供差异金额输入
- **US2 (P1)**: 依赖 T005/T009 的领域改造；为 US4 提供审计触发点
- **US3 (P2)**: 独立性强，仅依赖配置与 ADR-0021
- **US4 (P3)**: 汇总 US1/US2 的指标与审计

### Parallel Opportunities

- T002/T003/T004、T006~T010 可并行
- 各 US 内测试任务（T011~T014、T020~T024、T030~T032、T037~T039）彼此可并行
- US1 与 US3 分属不同文件（loader vs feign config），可并行开发

---

## Implementation Strategy

### MVP First（US1 + Foundational）

1. Setup（T001 批准 ADR + T002/T003/T004）
2. Foundational（T005~T010）
3. US1（T011~T019）→ **停下验证**：两个周期结果不同、回退留痕、幂等重跑
4. 验证通过后再继续

### Incremental Delivery

1. Setup + Foundational → 领域可承载生命周期与账单来源
2. +US1 → 验证（按周期对账、差异可查询）
3. +US2 → 验证（差异处理 → PROCESSING → CLOSED，门禁生效）
4. +US3 → 验证（超时/重试/失败不入批）
5. +US4 → 验证（指标/审计/响应口径）
6. Polish → 全量回归与文档同步

---

## Notes

- [P] = 不同文件、无依赖，可并行；[Story] 标签映射到 spec 用户故事
- 所有金额改动 MUST 保持 `long` 最小货币单位，禁 `float`/`double`（Constitution §II.1）
- 状态迁移 MUST 经 `ReconciliationBatch` 四个领域方法唯一入口（Constitution §V.2）；**MUST NOT** 新增 `setStatus`
- 跨服务同步 RPC + 幂等，不引入 MQ / 2PC / Resilience4j（ADR-0001、ADR-0021、Constitution §IV）
- 对账 MUST NOT 写 payment/refund 任何数据（INV-6，Constitution §III 边界 #4）
- **MUST NOT** 删测试或改测试迎合错误实现；`ReconciliationBatchStateMachineTest` 既有 6 个用例 MUST 保持通过（Constitution §VIII.3/4）
- 实现前务必先确认 ADR-0019~0021（Constitution §VIII.6 / Governance §8.3/§8.4/§8.8）
