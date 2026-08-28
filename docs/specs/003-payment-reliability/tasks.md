# Tasks: 支付可靠性（超时、UNKNOWN 收敛、有限重试与人工收敛）

**Input**: Design documents from `/specs/003-payment-reliability/` (spec.md, plan.md, research.md, data-model.md, contracts/manual-resolution.md, quickstart.md)

**Prerequisites**: plan.md ✅、spec.md ✅、research.md ✅、data-model.md ✅、contracts/ ✅

**Current Progress（2026-08-28）**: Feature 003 实现完成并通过测试（payment-service 63 tests 全过）。
- US1（超时→UNKNOWN）：T001/T002/T006/T007/T008/T009 ✅
- US2（主动查询收敛）：T005/T010/T011/T012 ✅（`QueryStatusRequest` + `ChannelQueryService` + `ChannelQueryScheduler` + `ChannelQueryTest`）
- US3（有限重试与耗尽）：T003/T013/T014/T015 ✅（按 ADR-0012~0014 最简实现：`errorType`/`nextRetryAt` 两列 + `PaymentRetryService`/`PaymentRetryScheduler` + `PaymentRetryTest`）
- US5（指标与真实收敛时长）：T019/T020 ✅（`ReliabilityMetricsTest` + `enteredUnknownAt` 真实时长，ADR-0015）；T021（告警面板）交 009 Observability Baseline
- T022（终态冲突 ADR-0007）✅ `TerminalConflictTest`
- US4/ADR-0006 保持 Deferred（Phase 9）；实现期新决策见 `docs/adr/0005-payment-reliability-impl-decisions.md`（ADR-0012~0015，Proposed 待确认）

**Tests**: 本 Feature 资金正确性敏感，按 Constitution §VII 与 spec 要求，**MUST** 包含测试任务（已内联到各 US 阶段）。

**Organization**: 按用户故事分组（US1~US5），每个故事可独立实现与测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1~US5）
- 描述含准确文件路径

## Path Conventions

- 模块根：`payment-service/src/main/java/com/payment/payment/`
- 测试根：`payment-service/src/test/java/com/payment/payment/`
- 仅在 `payment-service` 自有 Schema 内改动

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 确认决策与配置骨架

- [x] T001 ADR-0003~0007 经负责人 2026-08-28 确认：ADR-0003/0004/0005/0007 = **Accepted**，ADR-0006（人工收敛）= **Not Implemented / Deferred**（延后 Phase 9）；决策见 `docs/adr/0003-payment-reliability-decisions.md`
- [x] T002 [P] 在 `payment-service` 增加可靠性配置（`application.yml` + `@ConfigurationProperties` 类 `ReliabilityConfig.java`）：超时阈值（默认 30s）、超时扫描间隔（默认 10s）、重试上限（默认 3）、退避序列（默认 1s/2s/4s）、查询尝试上限（默认 5）；均可配置覆盖

---

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [x] T003 扩展 `domain/PaymentAttempt.java` 与 `infra/persistence/attempt/PaymentAttemptEntity.java`、`PaymentAttemptMapper.java`：新增 `errorType`(TRANSIENT/HARD/UNKNOWN)、`nextRetryAt`（按 ADR-0013 最简：`attemptIndex` 复用 `retryCount`、`finishedAt` 复用 `respondedAt`）
- [x] T004 [P] `Payment` 持久化层补全列：`queryAttempts`、`enteredUnknownAt`（ADR-0015 取代「复用 updatedAt 推导」方案）；`resolvedBy`/`resolvedAt` 随 ADR-0006 人工收敛延后，暂不实现
- [x] T005 [P] `PaymentChannel.queryStatus(QueryStatusRequest)` 能力；Mock Channel 支持 SUCCESS / FAILURE / TIMEOUT / TRANSIENT 四态（含 `setQueryResult` 注入查询收敛结果）
- [x] T006 [P] 可靠性指标与审计底座：直接复用既有 `BusinessMetrics`（counter/timer）与 `StructuredAuditLogger`，无需新建底座；`payment.timeout` 已在 `TimeoutScanner` 中按既有模式 `metrics.counter("payment.timeout", 1.0, "module", "payment")` 发计数（US1 已用；US3/US5 沿用同一模式）

**Checkpoint**: 基础就绪，用户故事可并行开始

---

## Phase 3: User Story 1 - 超时进入 UNKNOWN（Priority: P1）🎯 MVP

**Goal**: PROCESSING 超阈值 → UNKNOWN，不猜成败，订单/交易不推进

**Independent Test**: 制造一笔渠道挂起的支付，等待 > 超时阈值，断言支付 UNKNOWN、订单 PENDING、payment.timeout 计数 +1

### Tests for US1

- [x] T007 [P] [US1] `TimeoutScanTest` in `src/test/java/com/payment/payment/application/reliability/TimeoutScanTest.java`：超阈值进 UNKNOWN；非 PROCESSING 被跳过不覆盖；近期 PROCESSING 不误判；二次扫描幂等

### Implementation for US1

- [x] T008 [US1] 实现 `application/reliability/TimeoutScanner.java` + `TimeoutScanScheduler.java`（`@EnableScheduling` 已在 `PaymentApplication` 启用）：`@Scheduled` 扫描 PROCESSING 且 `attempt.requestedAt < now - timeout` 的支付 → `payment.markUnknown("TIMEOUT")`（仅 PROCESSING，见 FR-001/ADR-0004）
- [x] T009 [US1] 在超时收敛处发 `payment.timeout` 计数（FR-002/FR-010）；「UNKNOWN 堆积」业务告警信号由 Phase 9 / 009 Observability Baseline 统一接入（本任务完成计数侧，告警侧见 US5/T021）

**Checkpoint**: US1 可独立验证

---

## Phase 4: User Story 2 - 主动查询/回调收敛（Priority: P1）

**Goal**: UNKNOWN 经主动查询渠道收敛为终态，只触发一次下游

**Independent Test**: 置 UNKNOWN，查询调度器拿到 SUCCESS → 支付 SUCCEEDED、订单 PAID 仅一次；查询上限后仍不明确则保持 UNKNOWN

### Tests for US2

- [x] T010 [P] [US2] `ChannelQueryTest` in `.../application/reliability/ChannelQueryTest.java`：查询收敛 SUCCESS/FAILED 且下游只一次；已收敛不再重复查询；达查询上限保持 UNKNOWN

### Implementation for US2

- [x] T011 [US2] 实现 `application/reliability/ChannelQueryService.java` + `ChannelQueryScheduler.java`：扫描 UNKNOWN → `PaymentChannel.queryStatus(...)` → `PaymentUnknownResolutionService.resolve(id, result)`；`queryAttempts` 达上限停止自动查询（FR-003/FR-004/ADR-0003）
- [x] T012 [US2] 复用既有 `PaymentUnknownResolutionService`（不重复实现），仅新增触发来源

**Checkpoint**: US1+US2 均可独立工作

---

## Phase 5: User Story 3 - 有限重试与耗尽（Priority: P2）

**Goal**: 幂等调用瞬时失败按上限+退避重试；硬拒绝不重试；耗尽不确定→UNKNOWN

**Independent Test**: 瞬时失败 2 次后成功 → 最终 SUCCEEDED 且重试计数反映；硬拒绝 → FAILED 且 attemptCount=1；耗尽 → UNKNOWN

### Tests for US3

- [x] T013 [P] [US3] `PaymentRetryTest` in `.../application/reliability/PaymentRetryTest.java`：退避重试后成功；硬拒绝 0 重试；渠道 UNKNOWN 不重试；耗尽→UNKNOWN

### Implementation for US3

- [x] T014 [US3] 实现 `application/reliability/PaymentRetryService.java` + `PaymentRetryScheduler.java`：按 `PaymentAttempt`（errorType=TRANSIENT 且尝试次数<上限）在 `nextRetryAt` 重放渠道调用（同 attempt/同幂等键，ADR-0014）；硬拒绝→直接 FAILED（走既有应用流程）；耗尽且不确定→`markUnknown("RETRY_EXHAUSTED")`（FR-005/FR-006/FR-007/ADR-0005）
- [x] T015 [US3] 重试/耗尽事件发 `payment.retry` / `payment.retry_exhausted` 计数（FR-010）

**Checkpoint**: US1~US3 可独立工作

---

## Phase 6: User Story 4 - 人工收敛（Priority: P2）— **【Deferred / Not Implemented，见 ADR-0006】**

> **本阶段不实现**。人工收敛（FR-008/FR-009、spec US4）对应的 ADR-0006 已被负责人决定 **Not Implemented**，延后至路线图 Phase 9（Risk / Security）统一建设权限/审计体系。本阶段自动收敛（主动查询 + 超时 + 重试）覆盖绝大多数 UNKNOWN；剩余者保持 UNKNOWN 由对账兜底。以下任务冻结，待 Phase 9 重新立项。

**Goal（冻结）**: 受控端点将 UNKNOWN 裁定为成功/失败，强制理由 + 审计，只一次下游

### Tests for US4（冻结）

- [ ] T016 [P] [US4] `ManualResolutionTest` in `.../api/internal/ManualResolutionTest.java`：裁定 SUCCESS 推进一次+审计；缺理由/无权限拒；已终态拒（ADR-0006/0007）— **Deferred**

### Implementation for US4（冻结）

- [ ] T017 [US4] 实现 `application/ManualResolutionService.java` + `api/internal/PaymentResolutionController.java`（`POST /internal/payments/{id}/resolve`）：仅 UNKNOWN 可裁定、MUST 带 reason、写 FINANCIAL_AUDIT、复用 `resolve`（FR-008/FR-009）— **Deferred**
- [ ] T018 [US4] 端点权限受控（Phase 9）— **Deferred**

**Checkpoint**: US1~US4 可独立工作

---

## Phase 7: User Story 5 - 可靠性指标与告警（Priority: P3）

**Goal**: UNKNOWN 真实时长可度量；超时/重试耗尽/人工收敛有指标；UNKNOWN 堆积与重试耗尽可告警

**Independent Test**: 触发超时/重试耗尽/人工收敛，断言对应计数器递增；制造 UNKNOWN 堆积断言业务告警

### Tests for US5

- [x] T019 [P] [US5] `ReliabilityMetricsTest` in `.../application/reliability/ReliabilityMetricsTest.java`：payment.timeout / payment.retry / payment.retry_exhausted / payment.query 计数递增；`payment.unknown.duration` 计时器落盘

### Implementation for US5

- [x] T020 [US5] 修复 `PaymentUnknownResolutionService` 以 `Duration.ZERO` 记录时长的缺口：`Payment.markUnknown` 记录 `enteredUnknownAt`，收敛时计算真实时长（ADR-0015）
- [ ] T021 [US5] 配置「UNKNOWN 堆积」「重试耗尽」业务告警面板 — **移交 009 Observability Baseline**：本 Feature 已产出全部计数器（`payment.timeout` / `payment.retry` / `payment.retry_exhausted` / `payment.query` / `payment.unknown.duration`），告警规则与 Grafana 面板由 009 统一建设（Constitution §VII.4）

**Checkpoint**: 全部 US 可独立工作

---

## Phase 8: Polish & Cross-Cutting

- [x] T022 [P] 补 `TerminalConflictTest`（`.../application/reliability/TerminalConflictTest.java`）：先 FAILURE 后 SUCCESS → 保持 FAILED；先 SUCCESS 后 FAILURE/UNKNOWN → 保持 SUCCEEDED（ADR-0007 不变量，FR-011）
- [x] T023 [P] 运行 `mvnw verify` 全量通过（本环境 `./mvnw` 启动器损坏，改用 `mvn.cmd verify`，63 tests 全过）；手动 e2e 见 quickstart.md（需本地 MySQL）
- [x] T024 对照 spec SC-001~SC-005 / FR-001~FR-012 回检缺口，产出 acceptance.md
- [x] T025 更新 `docs/architecture/roadmap.md`：003 标记为已实现，Next Feature 指 004-ledger（SOP 第 9 步）
- [ ] T026 Review：运行 `/review`；涉及支付走 `/payment-review`（SOP 第 8 步）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 门禁（ADR 批准）先于一切实现；T002 配置骨架可并行
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞**所有 US
- **US1~US5 (Phase 3+)**: 依赖 Foundational；可按优先级 P1→P3 顺序，或并行（若人力）
- **Polish (Phase 8)**: 依赖全部 US

### User Story Dependencies

- **US1 (P1)**: Foundational 后开始，无跨故事依赖（MVP）
- **US2 (P1)**: 复用 `PaymentUnknownResolutionService`（Foundational 提供），可独立测试
- **US3 (P2)**: 依赖 PaymentAttempt 扩展（T003），可独立测试
- **US4 (P2)**: 依赖 resolve 入口与审计底座（T006），可独立测试
- **US5 (P3)**: 依赖前述指标底座，可独立测试

### Parallel Opportunities

- T002/T003/T004/T005/T006 中标注 [P] 者可并行
- Foundational 完成后，US1~US5 可由不同开发者并行
- 各 US 的测试任务 [P] 可并行

---

## Parallel Example: User Story 1

```bash
# 测试先行（应失败）
Task: "TimeoutScanTest in .../application/TimeoutScanTest.java"
# 实现
Task: "实现 TimeoutScanScheduler.java（PROCESSING→UNKNOWN）"
Task: "超时事件发 payment.timeout 计数"
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. Setup（T001 批准 ADR + T002 配置）
2. Foundational（T003~T006）
3. US1（T007~T009）→ **停下验证**：制造挂起支付，断言 UNKNOWN + 订单 PENDING + 指标
4. 验证通过后再继续

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. +US1 → 验证（MVP）
3. +US2 → 验证（收敛能力）
4. +US3 → 验证（重试）
5. +US4 → 验证（人工兜底）
6. +US5 → 验证（可观测）
7. 每步独立可测，不破坏前序

---

## Notes

- [P] = 不同文件、无依赖，可并行
- [Story] 标签映射到 spec 用户故事，便于追溯
- 所有状态迁移经状态机唯一入口 + 乐观锁（FR-011）；**MUST NOT** 删测试或改测试迎合错误实现（Constitution §VIII.3/4）
- 跨服务沿用同步 RPC + 幂等，不引入 MQ/2PC（ADR-0001、§IV）
- 实现前务必先确认 ADR-0003~0007（Constitution §VIII.6 / Governance §8.5/§8.8）
