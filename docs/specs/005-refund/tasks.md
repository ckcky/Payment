# Tasks: Refund 退款（缺口补齐）

**Input**: Design documents from `/specs/005-refund/` (spec.md, plan.md, data-model.md, contracts/refund-orchestration.md, quickstart.md)

**Prerequisites**: spec.md ✅、plan.md ✅、data-model.md ✅、contracts/ ✅、checklists/ ✅、acceptance.md ✅、quickstart.md ✅

**Current Progress（2026-08-29）**: 文档先行阶段已完成。**实现状态：未开始**。ADR-0016~0018 状态 **Proposed**，待负责人确认（Constitution §8.3/§8.4/§8.8）。

**Tests**: 本 Feature 资金正确性敏感，按 Constitution §VII 与 spec FR-017，**MUST** 包含测试任务（已内联到各 US 阶段）；**MUST NOT** 删测试或改测试迎合错误实现。

**Organization**: 按用户故事分组（US1~US5）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1~US5）
- 描述含准确文件路径

## Path Conventions

- 退款模块根：`refund-service/src/main/java/com/payment/refund/`
- 履约模块根：`fulfillment-service/src/main/java/com/payment/fulfillment/`
- 契约 DTO：`common/common-dto/src/main/java/com/payment/common/dto/rpc/`
- 测试根：各模块 `src/test/java/com/payment/<svc>/`
- Schema DDL：`deployment/schema/06-refund-schema.sql`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 确认决策与 schema 基线

- [ ] T001 负责人确认 ADR-0016~0018（`docs/adr/0006-refund-decisions.md`），更新状态为 Accepted —— **实现门禁（Constitution §8）**
- [ ] T002 [P] 修改 `deployment/schema/06-refund-schema.sql`：`refunds` 增列 `refunded_amount_minor BIGINT NOT NULL DEFAULT 0`（见 data-model.md §2）
- [ ] T003 [P] 修改 `deployment/schema/06-refund-schema.sql`：新建 `refund_post_process_attempts` 表 + `idx_pp_refund_target` 索引（见 data-model.md §4）

---

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [ ] T004 修改 `domain/Refund.java`：新增 `refundedAmountMinor` 字段与构造/`rehydrate`/getter；`partiallySucceed(long refundedMinor)` 重载（校验 `0 < refunded < amountMinor`）；`succeed()` 语义收敛为「全额成功」并置 `refundedAmountMinor = amountMinor`（`Refund.java:81/86`）
- [ ] T005 [P] 修改 `infra/persistence/refund/RefundEntity.java` + `RefundMapper.java`：`refunded_amount_minor` 映射；`rehydrate` 参数同步（含 `MybatisRefundRepository`、测试用 `InMemoryRefundRepository`）
- [ ] T006 [P] 修改 `domain/RefundPolicy.java`：累计口径改为「终态计已确认额、在途（`PROCESSING`/`UNKNOWN`）计申请额」（data-model.md §3）
- [ ] T007 [P] 修改 `application/CreateRefundCommand.java` / `api/RefundResponse.java`：响应暴露 `refundedAmountMinor`（契约向后兼容：仅新增）

**Checkpoint**: 领域与持久层可承载部分退款金额，累计口径正确

---

## Phase 3: User Story 1 - 部分退款可追踪且累计不超限（Priority: P1）🎯 MVP

**Goal**: 让 `PARTIALLY_SUCCEEDED` 端到端可达，`refundedAmountMinor` 可追踪，累计额度不超限（缺口 G1）

**Independent Test**: 申请 1000、渠道退 300 → `PARTIALLY_SUCCEEDED` + `refundedAmountMinor=300`；再申请 700 成功；再申请 1 被 `REJECTED`

### Tests for US1

- [ ] T008 [P] [US1] `domain/RefundAmountInvariantTest`（扩展）：`0 < refunded < amount` → PARTIALLY_SUCCEEDED；`refunded == amount` → SUCCEEDED；`refunded <= 0` / `> amount` 被拒
- [ ] T009 [P] [US1] `domain/RefundPolicyAccumulationTest`：终态按已确认额、在途按申请额累计；超额被拒；部分成功后剩余额度申请被批准
- [ ] T010 [P] [US1] `integration/PartialRefundScenarioTest`（Testcontainers）：部分退款落库 + 剩余额度二次退款 + 超额被拒

### Implementation for US1

- [ ] T011 [US1] 修改 `common-dto` 的 `rpc/RefundAttemptResponse.java`：新增 `refundedAmountMinor`（向后兼容）
- [ ] T012 [US1] 修改 `payment-service/.../application/PaymentRefundService.java` + `api/RefundRpcController.java`：回传渠道实际退款金额（Mock Channel 支持部分退回）
- [ ] T013 [US1] 修改 `application/PaymentRefundGateway.java`：透传 `refundedAmountMinor`
- [ ] T014 [US1] 修改 `application/RefundApplicationService.java:99-103`：按 `SUCCEEDED/FAILED/UNKNOWN` + 实际金额驱动状态机（`r == amount` → succeed；`0 < r < amount` → partiallySucceed；非法金额 → UNKNOWN + 告警）

**Checkpoint**: US1 可独立验证

---

## Phase 4: User Story 2 - 后处理编排完整且失败可独立追踪（Priority: P1）

**Goal**: 补齐 refund → fulfillment RPC，两侧后处理失败可追踪且不回滚退款成功（缺口 G2）

**Independent Test**: 令 fulfillment/entitlement 后处理均抛异常 → 退款仍 `SUCCEEDED`，两条失败尝试可查询，`refund.post_process_failed` 递增

### Tests for US2

- [ ] T015 [P] [US2] `application/RefundPostProcessOrchestratorTest`：两侧 RPC 均触发；单侧失败不影响退款成功且被记录；幂等重复触发不重复调用
- [ ] T016 [P] [US2] `integration/RefundPostProcessFailureTest`：后处理失败不回滚退款成功（替换现有 `catch (RuntimeException ignored)` 静默路径）
- [ ] T017 [P] [US2] `fulfillment-service` 侧 `RefundRpcControllerTest`：PENDING 履约被取消；DELIVERED 履约返回可解释结果（不抛异常）

### Implementation for US2

- [ ] T018 [P] [US2] 新增 `domain/RefundPostProcessTarget.java`（FULFILLMENT / ENTITLEMENT / LEDGER）与 `domain/RefundPostProcessAttempt.java`
- [ ] T019 [P] [US2] 新增 `common-dto` 的 `rpc/RefundFulfillmentRequest.java` / `RefundFulfillmentResponse.java`（契约见 contracts/refund-orchestration.md §2）
- [ ] T020 [US2] 新增 `application/FulfillmentGateway.java` 出站端口 + `infra/client/FulfillmentFeignClient.java`（`services.fulfillment.url` 配置，默认 `http://localhost:8086`）
- [ ] T021 [US2] `fulfillment-service`：新增 `api/RefundRpcController.java`（`POST /internal/fulfillments/on-refund`）+ `application/FulfillmentApplicationService` 退款撤销用例（仅 `PENDING → CANCELLED`，其他状态返回可解释结果）
- [ ] T022 [US2] 新增 `application/RefundPostProcessOrchestrator.java`：确认退款后依次调用 fulfillment + entitlement，每次调用落 `RefundPostProcessAttempt`，失败记指标 `refund.post_process_failed` + `FINANCIAL_AUDIT`，**不回滚**退款成功
- [ ] T023 [US2] 改造 `application/RefundApplicationService.java:108-116`：以 `RefundPostProcessOrchestrator` 替换内联的 entitlement 调用与静默 catch
- [ ] T024 [P] [US2] 新增 `infra/persistence/refund/RefundPostProcessAttemptMapper.java` / `Entity.java`（`refund_post_process_attempts` 读写）

**Checkpoint**: US1+US2 可独立工作

---

## Phase 5: User Story 3 - 幂等、未知不重复执行、收敛防御边界（Priority: P2）

**Goal**: `resolve` 显式前置断言；UNKNOWN 不重复资金动作、不触发后处理（缺口 G3）

**Independent Test**: `REQUESTED` 退款调 resolve → 明确 `STATE_TRANSITION_VIOLATION`；UNKNOWN 重复 resolve → 只收敛一次、只触发一次后处理

### Tests for US3

- [ ] T025 [P] [US3] `application/RefundRpcCallbackServiceTest`：`REQUESTED` 被显式拒绝；`UNKNOWN` 收敛成功一次；终态重复 resolve 幂等吸收
- [ ] T026 [P] [US3] `integration/UnknownRefundIdempotencyTest`：UNKNOWN 期间无第二次渠道尝试、无后处理、无记账

### Implementation for US3

- [ ] T027 [US3] 修改 `application/RefundRpcCallbackService.java:24-36`：加入 `requireStatus(UNKNOWN)` 防御断言；终态显式幂等吸收（返回当前状态）
- [ ] T028 [US3] 修改 `domain/Refund.java`：暴露 `requireStatus`/`isTerminal` 供应用层断言（保持状态迁移唯一入口，不新增 setStatus）
- [ ] T029 [US3] 修改 `application/RefundRpcCallbackService.java`：收敛为成功类状态时触发同一套后处理/记账编排（与 US2/US4 共用入口，保证「只一次」）

**Checkpoint**: US1~US3 可独立工作

---

## Phase 6: User Story 4 - 确认退款记账入 ledger（Priority: P2）

**Goal**: 已确认退款以实际退款金额向 `ledger-service` 记平衡冲正分录（承接 `004-ledger` US2 在退款侧缺口）

**Independent Test**: 退款 SUCCEEDED → 账本存在 `REFUND:<refundIdempotencyKey>` 平衡 Posting；部分成功按 300 而非 1000 记账；重复记账幂等

### Tests for US4

- [ ] T030 [P] [US4] `application/RefundLedgerPostingTest`：记账金额为 `refundedAmountMinor`；幂等键格式正确；重复吸收
- [ ] T031 [P] [US4] `integration/RefundLedgerPostingFailureTest`：账本不可用/超时不回滚退款成功，记 `ledger.posting_failed` 并落到后处理尝试记录

### Implementation for US4

- [ ] T032 [US4] 新增 `application/LedgerPostingGateway.java` 出站端口 + `infra/client/LedgerFeignClient.java`（`services.ledger.url` 配置，默认 `http://localhost:8090`），对齐 `payment-service/.../FeignLedgerPostingGateway.java`
- [ ] T033 [US4] 在确认退款路径调用记账（`RefundPostProcessOrchestrator` 内以 LEDGER 目标编排），金额 = `refundedAmountMinor`，幂等键 `REFUND:<refundIdempotencyKey>`（contracts §4）
- [ ] T034 [US4] 修改 `refund-service/src/main/resources/application.yml`：新增 `services.fulfillment.url` / `services.ledger.url`

**Checkpoint**: US1~US4 可独立工作

---

## Phase 7: User Story 5 - 可观测与对账事实覆盖部分成功（Priority: P3）

**Goal**: 全部退款分支有指标/审计；`confirmed-facts` 覆盖部分成功且金额口径正确

**Independent Test**: 部分成功退款 → `refund.partially_succeeded` 递增；`confirmed-facts` 返回金额 = 实际退款金额

### Tests for US5

- [ ] T035 [P] [US5] `application/RefundMetricsTest`（扩展）：`refund.partially_succeeded` / `refund.post_process_failed` 计数
- [ ] T036 [P] [US5] `application/RefundFactsServiceTest`（扩展）：`confirmed-facts` 覆盖 SUCCEEDED + PARTIALLY_SUCCEEDED，金额取 `refundedAmountMinor`

### Implementation for US5

- [ ] T037 [US5] 修改 `application/RefundApplicationService.java:141-157`（`recordFinalTransition`）：覆盖 `PARTIALLY_SUCCEEDED` 分支的指标与 `FINANCIAL_AUDIT`
- [ ] T038 [US5] 修改 `application/RefundFactsService.java:26-36`：事实集合扩展为已确认状态（SUCCEEDED + PARTIALLY_SUCCEEDED），金额取 `refundedAmountMinor`（口径按 ADR-0016）

**Checkpoint**: 全部 US 可独立工作

---

## Phase 8: Polish & Cross-Cutting

- [ ] T039 [P] 运行 `./mvnw verify` 全量通过（含 refund / fulfillment / payment / ledger 受影响模块）
- [ ] T040 [P] 按 `quickstart.md` 跑本地手动 e2e（全额退款 / 部分退款 / 重复幂等 / UNKNOWN 收敛 / 后处理失败 / 记账）
- [ ] T041 [P] 对照 spec SC-001~SC-006 / FR-001~FR-017 回检缺口，更新 `acceptance.md`
- [ ] T042 [P] 更新 `docs/architecture/systems/refund-service.md`：状态机图补 `partiallySucceed` 可达路径、§3.5 后处理与新增 fulfillment/ledger 网关、§6.4 新增指标键
- [ ] T043 [P] 更新 `docs/architecture/roadmap.md`：Current Status 推进 005；同步修正 `technical-solution.md:101` 的「骨架」标注与 §4.3.3 退款链路（按 ADR-0017 结论）
- [ ] T044 Review：运行 `/review`；涉及退款/资金路径运行 `/payment-review`（SOP 第 8 步）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 为门禁（ADR 批准）先于一切实现；T002/T003 可并行
- **Foundational (Phase 2)**: 依赖 Setup（schema 已改）；**阻塞**所有 US
- **US1 (Phase 3)**: Foundational 后开始（MVP，缺口 G1）
- **US2 (Phase 4)**: 依赖 Foundational；与 US1 可并行（不同文件，但 T023 依赖 T014 的分支结构）
- **US3 (Phase 5)**: 依赖 US2（共用后处理/记账入口，T029）
- **US4 (Phase 6)**: 依赖 US2 的 `RefundPostProcessOrchestrator`
- **US5 (Phase 7)**: 依赖 US1（部分成功分支存在）
- **Polish (Phase 8)**: 依赖全部 US

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始，是其他 US 的数据前提
- **US2 (P1)**: 依赖 Foundational 的领域实体；与 US1 松耦合
- **US3 (P2)**: 收敛入口需复用 US2 的编排入口以保证「只一次」
- **US4 (P2)**: 需 US1 的 `refundedAmountMinor` 作为记账金额
- **US5 (P3)**: 需 US1 的部分成功分支

### Parallel Opportunities

- T002/T003、T005/T006/T007 可并行
- 各 US 内测试任务（T008~T010、T015~T017、T025~T026、T030~T031、T035~T036）彼此可并行
- T018/T019/T024（新增文件）可并行；T020 与 T021（两侧端点）可并行开发后联调

---

## Implementation Strategy

### MVP First（US1 + Foundational）

1. Setup（T001 批准 ADR + T002/T003 schema）
2. Foundational（T004~T007）
3. US1（T008~T014）→ **停下验证**：部分退款可达、累计不超限
4. 验证通过后再继续

### Incremental Delivery

1. Setup + Foundational → 领域可承载部分金额
2. +US1 → 验证（部分/全额退款可追踪）
3. +US2 → 验证（后处理两侧编排 + 失败可追踪）
4. +US3 → 验证（收敛防御 + 幂等）
5. +US4 → 验证（记账入账）
6. +US5 → 验证（指标/对账事实）
7. Polish → 全量回归与文档同步

---

## Notes

- [P] = 不同文件、无依赖，可并行；[Story] 标签映射到 spec 用户故事
- 所有金额改动 MUST 保持 `long` 最小货币单位，禁 `float`/`double`（Constitution §II.1）
- 状态迁移 MUST 经 `Refund.transitionTo` 唯一入口（Constitution §V.2）
- 跨服务同步 RPC + 幂等，不引入 MQ / 2PC（ADR-0001、Constitution §IV）
- **MUST NOT** 删测试或改测试迎合错误实现（Constitution §VIII.3/4）
- 实现前务必先确认 ADR-0016~0018（Constitution §VIII.6 / Governance §8.3/§8.4/§8.8）
