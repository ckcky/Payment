# Tasks: Ledger 资金账本（复式记账）

**Input**: Design documents from `/specs/004-ledger/` (spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md)

**Prerequisites**: plan.md ✅、spec.md ✅、research.md ✅、data-model.md ✅、contracts/ ✅

**Current Progress（2026-08-28）**: 文档先行阶段已完成（spec/plan/research/data-model/contracts/checklists/acceptance/quickstart）。ADR-0008~0011 状态 **Proposed**，待负责人确认后方可进入实现。本文件为后续实现阶段的任务清单（当前**未开始实现**）。

**Tests**: 本 Feature 资金正确性敏感，按 Constitution §VII 与 spec 要求，**MUST** 包含测试任务（已内联到各 US 阶段）。

**Organization**: 按用户故事分组（US1~US4）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1~US4）
- 描述含准确文件路径

## Path Conventions

- 账本模块根：`ledger-service/src/main/java/com/payment/ledger/`
- 调用方网关根：`payment-service/src/main/java/com/payment/payment/application/`（refund/settlement 同模式）
- 测试根：各模块 `src/test/java/com/payment/<svc>/`
- Schema DDL：`deployment/schema/09-ledger-schema.sql`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 确认决策与骨架

- [ ] T001 [P] 负责人确认 ADR-0008~0011（Constitution §8 人类决策边界），更新 `docs/adr/0004-ledger-design-decisions.md` 状态为 Accepted
- [ ] T002 [P] 新建 `ledger-service` 模块（pom.xml 继承父 POM；端口 8090；依赖 common-core/common-dto/MyBatis-Plus/OpenFeign/Micrometer）；`LedgerApplication` 启动类 + 上下文测试
- [ ] T003 [P] 编写 `deployment/schema/09-ledger-schema.sql`（`accounts`/`postings`/`ledger_entries` 三表 + 唯一约束 + 索引，见 data-model.md §2~§7）；接入 Flyway/Compose 应用机制（若已落地）

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [ ] T004 实现 `domain/Account.java`、`Posting.java`（聚合根，平衡校验 `isBalanced()`）、`LedgerEntry.java`（不可变值对象）、`LedgerRepository.java`
- [ ] T005 [P] 实现 `application/LedgerPostingService.java`：`validate → checkBalance → persistInTxn → audit`；借贷不平衡拒绝（`UNBALANCED`）；幂等键唯一约束 + `DuplicateKeyException` 回查
- [ ] T006 [P] 实现 `application/BalanceChecker.java`：全局借贷平衡性校验（按币种聚合 `sum(debit) - sum(credit)` 应 = 0）
- [ ] T007 [P] 实现 `api/LedgerController.java` + `api/dto/PostingRequest.java` + `common-dto` 记账 RPC DTO；仅暴露内部记账端点
- [ ] T008 [P] 资金审计：`FINANCIAL_AUDIT` 记录每次成功记账（来源/金额/科目/前后余额摘要）；指标 `ledger.posted` / `ledger.posting_failed`

**Checkpoint**: 账本服务可独立记账与校验

---

## Phase 3: User Story 1 - 支付成功记账（Priority: P1）🎯 MVP

**Goal**: 支付 SUCCEEDED → 账本平衡 Posting（PAYMENT 来源）

**Independent Test**: 支付 SUCCEEDED 触发记账，断言 Posting 借贷平衡、source=PAYMENT、可回查

### Tests for US1

- [ ] T009 [P] [US1] `LedgerPostingServiceTest`：支付记账借贷平衡；重复幂等吸收；不平衡拒绝
- [ ] T010 [P] [US1] `PaymentCapturePostingIntegrationTest`（Testcontainers）：端到端支付成功→账本落分录

### Implementation for US1

- [ ] T011 [US1] payment-service 实现 `application/LedgerPostingGateway.java`（Feign → ledger-service，沿用 `ResilientFulfillmentGateway` 重试/超时模式）
- [ ] T012 [US1] 在支付成功路径（payment-service 既有成功回写处）调用 `LedgerPostingGateway.postPaymentCapture(...)`；失败入「待记账」兜底（不回滚支付成功，ADR-0009）

**Checkpoint**: US1 可独立验证

---

## Phase 4: User Story 2 - 退款记账（Priority: P1）

**Goal**: 退款确认 → 账本平衡冲正 Posting（REFUND 来源）

**Independent Test**: 退款触发冲正，商户应付减少，全局仍平衡

### Tests for US2

- [ ] T013 [P] [US2] `RefundPostingTest`：退款冲正平衡；重复幂等吸收

### Implementation for US2

- [ ] T014 [US2] refund-service 实现 `LedgerPostingGateway.java` + 在退款确认路径调用 `postRefund(...)`

**Checkpoint**: US1+US2 可独立工作

---

## Phase 5: User Story 3 - 结算记账（Priority: P2）

**Goal**: 结算批次生成 → 账本「应付→已结」平衡 Posting（SETTLEMENT 来源）

**Independent Test**: 结算批次触发记账，商户应付减少 S、结算应付增加 S

### Tests for US3

- [ ] T015 [P] [US3] `SettlementPostingTest`：结算记账平衡；重复幂等吸收

### Implementation for US3

- [ ] T016 [US3] settlement-service 实现 `LedgerPostingGateway.java` + 在批次结算路径调用 `postSettlement(...)`

**Checkpoint**: US1~US3 可独立工作

---

## Phase 6: User Story 4 - 平衡性校验与追溯（Priority: P2）

**Goal**: 全局借贷恒等可校验；任意分录可追溯到业务来源

**Independent Test**: 任意记账序列后 `BalanceChecker` 返回平衡；source 可追溯

### Tests for US4

- [ ] T017 [P] [US4] `BalanceCheckerTest`：多笔后全局平衡；注入不平衡被拒
- [ ] T018 [P] [US4] `SourceTraceabilityTest`：按 source_type/source_id 回查分录

### Implementation for US4

- [ ] T019 [US4] 暴露平衡性校验端点/查询（供 reconciliation 调用）；确保 `LedgerEntry` 冗余 `source_type/source_id` 索引可用

**Checkpoint**: 全部 US 可独立工作

---

## Phase 7: Polish & Cross-Cutting

- [ ] T020 [P] 补 `LedgerIdempotencyTest`：并发重复记账不重复分录（DB 唯一约束兜底）
- [ ] T021 [P] 运行 `mvnw verify` 全量通过；按 quickstart.md 跑本地手动 e2e
- [ ] T022 对照 spec SC-001~SC-005 / FR-001~FR-011 回检缺口，更新 acceptance.md
- [ ] T023 更新 `docs/architecture/roadmap.md`：Current Status 推进 004；更新 `docs/architecture/systems/ledger-service.md`
- [ ] T024 Review：运行 `/review`；涉及 Ledger 运行 `/payment-review`（SOP 第 8 步）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 门禁（ADR 批准）先于一切实现；T002/T003 可并行
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞**所有 US
- **US1~US4 (Phase 3+)**: 依赖 Foundational；按 P1→P2 顺序或并行（若人力）
- **Polish (Phase 7)**: 依赖全部 US

### User Story Dependencies

- **US1 (P1)**: Foundational 后开始（MVP）
- **US2 (P1)**: 复用 US1 的账本服务与网关模式
- **US3 (P2)**: 复用账本服务与网关模式
- **US4 (P2)**: 依赖账本服务与索引

### Parallel Opportunities

- T002/T003/T004/T005/T006/T007/T008 中标注 [P] 者可并行
- Foundational 完成后，US1~US4 可由不同开发者并行
- 各 US 的测试任务 [P] 可并行

---

## Implementation Strategy

### MVP First（仅 US1）

1. Setup（T001 批准 ADR + T002/T003 骨架）
2. Foundational（T004~T008）
3. US1（T009~T012）→ **停下验证**：支付成功→账本平衡分录、幂等吸收
4. 验证通过后再继续

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. +US1 → 验证（支付记账）
3. +US2 → 验证（退款冲正）
4. +US3 → 验证（结算结转）
5. +US4 → 验证（平衡/追溯）
6. 每步独立可测，不破坏前序

---

## Notes

- [P] = 不同文件、无依赖，可并行
- [Story] 标签映射到 spec 用户故事，便于追溯
- 所有记账经聚合根平衡校验 + 幂等键唯一约束；**MUST NOT** 删测试或改测试迎合错误实现（Constitution §VIII.3/4）
- 跨服务同步 RPC + 幂等，不引入 MQ/2PC（ADR-0001、§IV）
- 实现前务必先确认 ADR-0008~0011（Constitution §VIII.6 / Governance §8.2/§8.3）
