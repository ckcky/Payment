# Tasks: Refund 退款（缺口补齐）

**Input**: Design documents from `/specs/005-refund/` (spec.md, plan.md, data-model.md, contracts/refund-orchestration.md, quickstart.md)

**Prerequisites**: spec.md ✅、plan.md ✅、data-model.md ✅、contracts/ ✅、checklists/ ✅、acceptance.md ✅、quickstart.md ✅

**Current Progress（2026-08-31）**: **实现已完成并验收**（`mvn -o clean verify -fae` 全量 15 模块 BUILD SUCCESS）。

> ## 裁决与落地（2026-08-30 裁决 / 2026-08-31 落地）
>
> | ADR | 裁决 | 对任务的影响 |
> |---|---|---|
> | **ADR-0016 部分退款** | ❌ **Rejected（不做）** | **US1（Phase 3）整体不做**；T002 / T004~T014 曾按最简实现落地，**已全部回退** |
> | **ADR-0017 refund→fulfillment 编排** | ✅ **Accepted** | US2（Phase 4）按计划落地 |
> | **ADR-0018 refund→ledger 记账** | ✅ **Accepted** | US4（Phase 6）按计划落地；记账金额取 `amountMinor`（全额退款恒为申请额） |
>
> 标记约定：`[x]` 已完成 · `[ ]` 未开始 · `[-]` **不做（延后/已回退）**。
> US1 的回退清单见 [ADR-0016 回退落地记录](../../adr/0006-refund-decisions.md)。

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

- [x] T001 负责人确认 ADR-0016~0018（`docs/adr/0006-refund-decisions.md`）—— **实现门禁（Constitution §8）** ✅ 2026-08-30 裁决：ADR-0016 **Rejected（部分退款不做）**；ADR-0017 / ADR-0018 **Accepted**
- [-] T002 [P] ~~`refunds` 增列 `refunded_amount_minor`~~ ⛔ **ADR-0016 裁决不做；曾加列，2026-08-31 已回退删除**（DDL / 测试 schema 均已移除；已部署环境需手工 `ALTER TABLE ... DROP COLUMN`）
- [x] T003 [P] `deployment/schema/06-refund-schema.sql` 新建 `refund_post_process_attempts` 表 + 索引 ✅

---

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [-] T004 ~~`Refund` 新增 `refundedAmountMinor`~~ ⛔ **已回退**：字段与 getter 删除、`rehydrate` 回到 13 参、`succeed()` 回到纯状态迁移（不写金额）。`partiallySucceed(long)` **保留但无调用方**（保留枚举以免 `RefundStatus.valueOf` 对历史行抛异常）
- [-] T005 [P] ~~`refunded_amount_minor` 持久化映射~~ ⛔ **已回退**（`RefundEntity` 字段/getter/setter、`MybatisRefundRepository` 读写一并删除）
- [-] T006 [P] ~~累计口径分态计数~~ ⛔ **不做**：`RefundPolicy` 未改动；累计**一律按申请额 `amountMinor`**（在途亦按申请额保守占用，防并发超退 H1）。用例见 T009
- [-] T007 [P] ~~`RefundResponse` 暴露 `refundedAmountMinor`~~ ⛔ **已回退**：回到 7 分量

**Checkpoint**: 领域与持久层可承载部分退款金额，累计口径正确

---

## Phase 3: User Story 1 - 部分退款可追踪且累计不超限（Priority: P1）🎯 MVP

**Goal**: 让 `PARTIALLY_SUCCEEDED` 端到端可达，`refundedAmountMinor` 可追踪，累计额度不超限（缺口 G1）

**Independent Test**: 申请 1000、渠道退 300 → `PARTIALLY_SUCCEEDED` + `refundedAmountMinor=300`；再申请 700 成功；再申请 1 被 `REJECTED`

### Tests for US1

- [-] T008 [P] [US1] ⛔ **不做**（ADR-0016 裁决）
- [x] T009 [P] [US1] ✅ **改口径后保留**：`RefundApplicationServiceTest#cumulativeCountsRequestedAmountForBothTerminalAndInTransit` —— 终态与在途**均按申请额**累计；第三笔令累计超 `paidAmount` 时 `REJECTED` **且不发起渠道尝试**（H1 防超退）
- [-] T010 [P] [US1] ⛔ **不做**（ADR-0016 裁决）

### Implementation for US1

- [-] T011 [US1] ~~`RefundAttemptResponse` 新增 `refundedAmountMinor`~~ ⛔ **已回退**：回到 3 分量 `(refundNo, status, channelReference)`
- [-] T012 [US1] ~~payment 侧回传实际退款金额~~ ⛔ **已回退**：`ChannelResult` 回到 5 分量、`MockChannelAdapter` 删除 `setRefundMinor` 与部分退款分支
- [-] T013 [US1] ⛔ **不做**（ADR-0016 裁决）
- [-] T014 [US1] ⛔ **不做**：`switch` 仍为三态（`SUCCEEDED` → `succeed()` / `FAILED` → `fail()` / 其余 → `markUnknown()`），渠道金额不参与状态推导

**Checkpoint**: US1 可独立验证

---

## Phase 4: User Story 2 - 后处理编排完整且失败可独立追踪（Priority: P1）

**Goal**: 补齐 refund → fulfillment RPC，两侧后处理失败可追踪且不回滚退款成功（缺口 G2）

**Independent Test**: 令 fulfillment/entitlement 后处理均抛异常 → 退款仍 `SUCCEEDED`，两条失败尝试可查询，`refund.post_process_failed` 递增

### Tests for US2

- [x] T015 [P] [US2] ✅ 行为由 `integration/RefundScenarioTest` 覆盖：`successfulRefundFiresAttemptAndPostProcessExactlyOnce` / `postProcessFailureDoesNotRollBackRefundSuccess` / `unknownRefundConvergesToSuccessAndPostProcessIsIdempotent`（未单独立文件）
- [x] T016 [P] [US2] ✅ 由 `RefundScenarioTest#postProcessFailureDoesNotRollBackRefundSuccess` 覆盖（静默 catch 已由 `RefundPostProcessOrchestrator` 的尝试记录 + 指标替代）
- [x] T017 [P] [US2] ✅ 2026-09-06 已补：`FulfillmentRefundControllerTest`（standalone MockMvc）覆盖 `POST /internal/fulfillments/on-refund` 的接线、请求透传与 CANCELLED/SKIPPED 响应映射

### Implementation for US2

- [x] T018 [P] [US2] ✅ `domain/RefundPostProcessAttempt.java`（含 `Target` 枚举 FULFILLMENT/ENTITLEMENT/LEDGER）+ `RefundPostProcessAttemptRepository` 端口
- [x] T019 [P] [US2] ✅
- [x] T020 [US2] ✅
- [x] T021 [US2] ✅ 落为 `api/FulfillmentRefundController.java` + `FulfillmentApplicationService` 撤销用例
- [x] T022 [US2] ✅ 编排顺序 fulfillment → entitlement → ledger；记账金额取 `refund.getAmountMinor()`（ADR-0016 回退后成功退款恒为全额）
- [x] T023 [US2] ✅
- [x] T024 [P] [US2] ✅ `RefundPostProcessAttemptMapper` / `RefundPostProcessAttemptEntity` / `MybatisRefundPostProcessAttemptRepository`

**Checkpoint**: US1+US2 可独立工作

---

## Phase 5: User Story 3 - 幂等、未知不重复执行、收敛防御边界（Priority: P2）

**Goal**: `resolve` 显式前置断言；UNKNOWN 不重复资金动作、不触发后处理（缺口 G3）

**Independent Test**: `REQUESTED` 退款调 resolve → 明确 `STATE_TRANSITION_VIOLATION`；UNKNOWN 重复 resolve → 只收敛一次、只触发一次后处理

### Tests for US3

- [x] T025 [P] [US3] ✅ 由 `RefundScenarioTest#unknownRefundConvergesToSuccessAndPostProcessIsIdempotent` + `#duplicateRefundDoesNotTriggerSecondFundAction` 覆盖（未单独立文件）
- [x] T026 [P] [US3] ✅ 由 `RefundApplicationServiceTest#unknownAttemptEndsUnknownWithoutPostProcess` 覆盖

### Implementation for US3

- [x] T027 [US3] ✅ 终态吸收由领域 `transitionTo` 保证（`succeed()/fail()` 对终态返回 `false`，`Refund.java:138`）；非 UNKNOWN 的误收敛被状态机静默吸收——**未抛显式异常**，与 spec 的「显式拒绝」存在偏差，已记为已知简化
- [x] T028 [US3] ✅ `requireStatus` / `isTerminal` 已在 `Refund` 内（private，`process()`/`reject()` 使用），未对外暴露
- [x] T029 [US3] ✅ 收敛成功后经同一 `RefundPostProcessOrchestrator` 入口触发

**Checkpoint**: US1~US3 可独立工作

---

## Phase 6: User Story 4 - 确认退款记账入 ledger（Priority: P2）

**Goal**: 已确认退款以实际退款金额向 `ledger-service` 记平衡冲正分录（承接 `004-ledger` US2 在退款侧缺口）

**Independent Test**: 退款 SUCCEEDED → 账本存在 `REFUND:<refundIdempotencyKey>` 平衡 Posting；部分成功按 300 而非 1000 记账；重复记账幂等

### Tests for US4

- [x] T030 [P] [US4] ✅ 2026-09-06 已补：`RefundLedgerPostingTest` 覆盖记账断言（幂等键格式 `REFUND:<idempotencyKey>`、sourceId=refundNo、申请全额、重复幂等吸收、REJECTED 不记账）
- [x] T031 [P] [US4] ✅ 2026-09-06 已补：`RefundPostProcessAttemptTest` 覆盖失败记账落 attempt（target=LEDGER、FAILED、重试 3 次、退款成功不回滚）

### Implementation for US4

- [x] T032 [US4] ✅ `application/LedgerPostingGateway` + `infra/client/LedgerFeignClient` + `infra/client/FeignLedgerPostingGateway`
- [x] T033 [US4] ✅ 金额 = **`refund.getAmountMinor()`**（ADR-0016 回退后成功退款恒为全额，无「实际退款金额」概念）
- [x] T034 [US4] ✅

**Checkpoint**: US1~US4 可独立工作

---

## Phase 7: User Story 5 - 可观测与对账事实覆盖部分成功（Priority: P3）

**Goal**: 全部退款分支有指标/审计；`confirmed-facts` 覆盖部分成功且金额口径正确

**Independent Test**: 部分成功退款 → `refund.partially_succeeded` 递增；`confirmed-facts` 返回金额 = 实际退款金额

### Tests for US5

- [x] T035 [P] [US5] ✅ `RefundMetricsTest` 覆盖 `created` / `duplicate` / `rejected` / `succeeded`；⚠️ `refund.partially_succeeded` 随 ADR-0016 **不做**
- [x] T036 [P] [US5] ✅ `RefundFactsServiceTest#confirmedFactsReturnsOnlySucceededRefunds`；`confirmed-facts` **仅返回 `SUCCEEDED`**（无 PARTIALLY_SUCCEEDED，ADR-0016 不做），金额取 `amountMinor`

### Implementation for US5

- [-] T037 [US5] ⛔ **不做**（无 PARTIALLY_SUCCEEDED 分支，ADR-0016 裁决）
- [-] T038 [US5] ⛔ **不做**（事实集合仍为 `SUCCEEDED`，金额取 `amountMinor`）

**Checkpoint**: 全部 US 可独立工作

---

## Phase 8: Polish & Cross-Cutting

- [x] T039 [P] ✅ `mvn -o clean verify -fae` 全量 15 模块 **BUILD SUCCESS**（2026-08-31）。注：本机 `./mvnw` 不可用，须用 `mvn.cmd -o`
- [x] T040 [P] ✅ 本地 e2e 场景已由 `RefundScenarioTest` + `RefundApplicationServiceTest` 自动化覆盖（**部分退款场景已移除**）
- [x] T041 [P] ✅ `acceptance.md` 已按裁决更新（2026-08-31）
- [x] T042 [P] ✅ 已更新（`partiallySucceed` 标注为**不可达**；补齐 fulfillment/ledger 网关与后处理编排说明）
- [x] T043 [P] ✅ 已更新
- [x] T044 ✅ 全量构建 + ArchUnit 边界测试通过

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

- **US1 (P1)**: ⛔ **整体不做**（ADR-0016 Rejected）。原「是其他 US 的数据前提」的依赖随之消失——US2/US4 改用 `amountMinor`
- **US2 (P1)**: 依赖 Foundational 的领域实体（已落地）
- **US3 (P2)**: 收敛入口复用 US2 的编排入口以保证「只一次」（已落地）
- **US4 (P2)**: ~~需 US1 的 `refundedAmountMinor`~~ → **改为 `amountMinor`**（已落地）
- **US5 (P3)**: ~~需 US1 的部分成功分支~~ → 仅覆盖 `SUCCEEDED`（已落地）

### Parallel Opportunities

- T002/T003、T005/T006/T007 可并行
- 各 US 内测试任务（T008~T010、T015~T017、T025~T026、T030~T031、T035~T036）彼此可并行
- T018/T019/T024（新增文件）可并行；T020 与 T021（两侧端点）可并行开发后联调

---

## Implementation Strategy

### MVP First（US1 + Foundational）

1. Setup（T001 批准 ADR + T002/T003 schema）
2. Foundational（T004~T007）
3. ~~US1（T008~T014）~~ ⛔ **不做**（ADR-0016）→ 改为验证「累计一律按申请额 + 超额 REJECTED 且不发起渠道尝试」
4. 验证通过后再继续

### Incremental Delivery

1. Setup + Foundational → 领域可承载部分金额
2. ~~+US1 → 验证（部分/全额退款可追踪）~~ ⛔ 不做（ADR-0016）
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
- 实现前务必先确认 ADR-0016~0018（Constitution §VIII.6 / Governance §8.3/§8.4/§8.8）—— ✅ 已于 2026-08-30 确认
- ~~**遗留测试债（已知，未闭环）**：T017（fulfillment 退款端点测试）、T030/T031（退款记账测试）~~ ✅ **2026-09-06 已全部补齐**（`FulfillmentRefundControllerTest` / `RefundLedgerPostingTest` / `RefundPostProcessAttemptTest`），测试债清零
