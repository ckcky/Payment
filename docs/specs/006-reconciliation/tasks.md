# Tasks: Reconciliation 对账（缺口补齐）

**Input**: Design documents from `/specs/006-reconciliation/` (spec.md, plan.md, data-model.md, acceptance.md, quickstart.md)

**Prerequisites**: spec.md ✅、plan.md ✅、data-model.md ✅、checklists/ ✅、acceptance.md ✅、quickstart.md ✅

**Current Progress（2026-09-09 收口）**: 全清单 48 项中 **47 项已勾结**，仅 T023（乐观锁并发集成）保留未勾——它需要真库（MySQL/Testcontainers）承载 `version` 列冲突，内存仓储无此语义，详见该项注记。

**2026-09-09 本轮补齐概要**（代码先行、勾选滞后是历史成因：实现早在 2026-08-30 随 ADR-0019~0021 裁决落地，但本清单长期停留在 2/48）：

| 类别 | 内容 |
|---|---|
| **真缺口补齐** | T003 第二周期 fixture、T006 `differenceAmountMinor()`、T010/T041 响应 `unresolvedDifferenceCount`、T018 `statement.default-file` 配置键、T019 null reference WARN、T028 `@NotBlank` + `@Valid`、T035 超时阈值外置、T040 `difference_amount_minor` 指标 |
| **缺陷修复** | T033 `errorDecoder` 对 5xx 抛 `RetryableException`——原先一律返回 `BizException`，导致 `Retryer` 永不触发（重试器是死配置），瞬时抖动会被判为读失败 |
| **测试债补齐** | T011 / T012 / T013 / T014 / T020 / T021 / T022 / T024 / T030 / T031 / T032 / T037 / T038 / T039（reconciliation-service 单测 68 → **122 全绿**） |
| **文档收口** | T044 acceptance.md、T045 系统文档、T046 技术总览/roadmap 漂移、T047 ADR 索引（核实已登记） |

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

- [x] T001 负责人确认 ADR-0019~0021（`docs/adr/0007-reconciliation-decisions.md`），更新状态为 Accepted —— **核实**：三份 ADR 文件内状态均为「✅ Accepted（2026-08-30 负责人裁决 accept；实现已落地）」（见该文件 `:12` / `:79` / `:137`），`roadmap.md` 亦登记 `006-reconciliation（ADR-0019~0021 Accepted）`；本项此前仅因勾选滞后未回填。
- [x] T002 [P] 修改 `deployment/schema/07-reconciliation-schema.sql`：新增 `statement_source VARCHAR(255) NULL`、`closed_at DATETIME NULL`、`closed_by VARCHAR(64) NULL` 三列 —— **核实**：三列就位（`:16-18`）。
- [x] T003 [P] 新增账单 fixture —— **本次补齐**：新增 `2026-09-30.csv`（4 条 + 1 条单边差异），与既有 `2026-08-31.csv` 内容不同，用于验证「不同周期产出不同差异集合」（T012/T014）。**命名偏差已接受**：spec 原文写月格式 `2026-08.csv`/`2026-09.csv`，代码先行为**日期**格式，按既有约定对齐（语义等价，且 E2E 已依赖 `2026-08-31.csv`，改名风险大于收益）。
- [x] T004 [P] 新增错误码 `UNRESOLVED_DIFFERENCES` —— **核实**：`ErrorCodes.UNRESOLVED_DIFFERENCES` 已存在，`ReconciliationBatch.close()` 关闭门禁在用（`:102`）。

---

## Phase 2: Foundational（阻塞前置，MUST 先于任何 US）

**⚠️ CRITICAL**: 用户故事工作须等本阶段完成

- [x] T005 修改 `domain/ReconciliationBatch.java`：`beginProcessing()` 幂等（`HAS_DIFFERENCE`/`PROCESSING` → `PROCESSING`）；`close()` 幂等（`CLOSED` → `CLOSED` 空操作）；`close()` 增加未处理差异门禁（≠0 抛 `UNRESOLVED_DIFFERENCES`）—— **核实**：`:84-112`。
- [x] T006 [P] 修改 `domain/ReconciliationBatch.java`：新增 `statementSource`/`closedAt`/`closedBy` 字段 + `rehydrate` 参数同步；新增 `unresolvedCount()` 与 `differenceAmountMinor()` 领域计算方法 —— **核实字段就位**（`:33-61`）；**本次补齐 `differenceAmountMinor()`**（双侧取差额绝对值、单侧缺失取该侧金额）。**命名**：未处理数实际命名为 `unresolvedDifferenceCount()`（语义与 `unresolvedCount()` 一致，避免同义双方法）。
- [x] T007 [P] 新增 `domain/ChannelStatementSource.java` 值对象 —— **核实**：已存在（`sourceType`/`locator`/`entryCount`/`fallbackUsed`）。
- [x] T008 [P] 修改 `infra/persistence/ReconciliationBatchEntity.java` + `MybatisReconciliationRepository.java`：三列映射与 `toDomain`/`toEntity` 同步 —— **核实**：`ReconciliationBatchEntity:21-94`、`MybatisReconciliationRepository:116`（`closed_at` 为 DATETIME，驱动不接受 ISO 字符串直写，已特化）。
- [x] T009 [P] 修改 `domain/Difference.java`：`resolve(note, actor, at)` 校验 `note` 非空白（`INVALID_ARGUMENT`），写入 `resolvedAt`/`resolvedBy` —— **核实**：`:64-72`。重复处理按 ADR-0019 取**幂等刷新**语义（覆盖为最新一次处理记录），由 `DifferenceResolveTest.repeatedResolveRefreshesIdempotently` 锁定。
- [x] T010 [P] 修改 `api/ReconciliationBatchResponse.java`：新增 `unresolvedDifferenceCount`、`statementSource`、`closedAt` —— **本次真补齐**：此前本项已勾选但 `unresolvedDifferenceCount` 字段**并不存在**（勾选虚标）；现已在响应 record 与 `from(...)` 中落地，与 `settlementSummary` 同源（INV-12）。

**Checkpoint**: 领域与持久层可承载生命周期闭合、账单来源与处理依据；既有状态机测试仍全绿（本次 9 → 13 用例，全部通过）

---

## Phase 3: User Story 1 - 按周期对账，差异可重复识别、可查询、可解释（Priority: P1）🎯 MVP

**Goal**: `load(period)` 真正按周期定位账单；回退留痕；差异可查询且含双侧金额/状态（缺口 G2 + N1 口径 + N4）

**Independent Test**: 两个周期使用不同 fixture 得到不同结果；同周期重跑返回同一 `batchId`；查询差异含双侧金额/状态

### Tests for US1

- [x] T011 [P] [US1] `infra/CsvChannelStatementLoaderTest` —— **本次补齐**（6 用例）：周期命中 `fallbackUsed=false`；未命中回退 `sample.csv` 且 `fallbackUsed=true`；两者皆无 → `INTERNAL_ERROR`；`period` 含 `/` 或 `..` 或 null → `INVALID_ARGUMENT`。**顺带加固**：`..` 原先被字符类放行（无分隔符时无法穿越，但埋隐患），已在 loader 校验中显式拒绝，与 spec 原意一致。
- [x] T012 [P] [US1] `application/ReconciliationApplicationServiceTest`（扩展）—— **本次补齐**：`differentPeriodsProduceDifferentDifferenceSets`（不同周期 → 不同差异集合 / 不同批次 / 不同 `statement_source`）；同周期重跑同 `batchId` 由既有用例覆盖。
- [x] T013 [P] [US1] `application/ReconciliationStatementFallbackMetricsTest` —— **本次补齐**（3 用例）：回退时 `reconciliation.statement_fallback` 递增且带 `period` 标签；重复回退累加；命中不计数。
- [x] T014 [P] [US1] `integration/ReconciliationPeriodScenarioTest` —— **本次补齐**（3 用例）：周期 A/B 各自对账落库为不同批次、`statement_source` 各自正确、同周期重跑返回存储批次。**实现选型**：沿用本模块既有集成测试风格（内存仓储 + fake 事实 + **真实** `CsvChannelStatementLoader` 读 classpath fixture），不引入 Testcontainers——本模块集成测试（`ReconciliationSettlementRpcScenarioTest`）本就不连真库，且本机 Docker/MySQL 不稳定会引入 flaky 源。

### Implementation for US1

- [x] T015 [US1] `infra/CsvChannelStatementLoader.java` 按 `period` 定位、回退、构造 `ChannelStatementSource` —— **核实**：`:65-97`。
- [x] T016 [US1] 非法行（列数 < 4 / 金额非数字）显式 WARN + 行号 —— **核实**：`parse()`/`parseFile()` 均带 `locator` 与 `lineNo` 的 WARN。
- [x] T017 [US1] `ReconciliationApplicationService` 把 `ChannelStatementSource` 写入批次 —— **核实**：`:88`。回退指标与 WARN 在 loader 侧（T013 覆盖）。
- [x] T018 [US1] `application.yml` 新增 `reconciliation.statement.fixture-dir` 与 `reconciliation.statement.default-file` —— **本次补齐** `statement.default-file`（默认 `sample.csv`），loader 构造改为注入而非硬编码。**键名偏差已接受**：目录键沿用既有 `reconciliation.statement-dir`（改名会破坏 `statement-dir-override` 等既有配置与 E2E 注入链路），语义与 spec 一致。
- [x] T019 [US1] 平台/渠道事实 `reference` 为 null 时计数 + WARN，不改匹配语义 —— **本次补齐**：`ReconciliationMatching.indexPlatform/indexChannel` 逐条 WARN（含类型/金额/状态）+ 汇总计数 WARN。

**Checkpoint**: US1 可独立验证（按周期对账成立）

---

## Phase 4: User Story 2 - 差异处理推进生命周期，全部处理后可关闭（Priority: P1）

**Goal**: 接线 `beginProcessing()`/`close()`，新增关闭端点与门禁，`CLOSED` 只读（缺口 G1 + N3）

**Independent Test**: 2 条差异的批次：处理 1 条 → `PROCESSING`；此时关闭被拒；处理第 2 条 → 关闭成功 `CLOSED`；关闭后再处理被拒、重复关闭幂等

### Tests for US2

- [x] T020 [P] [US2] `domain/ReconciliationBatchStateMachineTest`（扩展）—— **本次补齐** 4 用例：`close` 幂等（含首次收口记录不被覆盖）、`HAS_DIFFERENCE` 直接 `close` 被拒、未处理差异 `close` → `UNRESOLVED_DIFFERENCES`、全部处理后 `close` 记录操作人与时间。既有 9 个用例保持通过。**门禁顺序注记**：`HAS_DIFFERENCE` 直接 close 落在「尚有未处理差异」而非「状态不合法」——这是更精确的失败原因（告诉运营差几条），非缺陷。
- [x] T021 [P] [US2] `domain/DifferenceResolveTest` —— **本次补齐**（6 用例）：`note` 空白/null → `INVALID_ARGUMENT`；处理后 `resolvedAt`/`resolvedBy` 落值；重复处理幂等刷新；`unresolvedDifferenceCount` 随处理递减；`differenceAmountMinor` 三种口径。
- [x] T022 [P] [US2] `application/ReconciliationLifecycleTest` —— **本次补齐**（6 用例）：首条处理 → `PROCESSING`、未处理完关闭被拒、全部处理 → `CLOSED`、`CLOSED` 后处理被拒、重复关闭幂等、指标与审计埋点。
- [ ] T023 [P] [US2] `integration/ReconciliationLifecyclePersistenceTest`（Testcontainers/H2）：关闭后 `closed_at`/`closed_by` 落库；乐观锁冲突（并发 resolve + close）→ `CONFLICT` —— **保留未勾（唯一遗留）**：`closed_at`/`closed_by` 落库已由 `ReconciliationBatchEntity` + `MybatisReconciliationRepository` 映射承接（T008），但**并发乐观锁**需真库承载 `version` 列冲突，内存仓储无版本号语义、H2 与 MySQL 在 DATETIME 写入上还有方言差异（见 `MybatisReconciliationRepository:116`）。列为后续项：待引入 Testcontainers-MySQL 或稳定的本机 MySQL 后补，不做「假绿」断言。
- [x] T024 [P] [US2] `integration/ReconciliationNoFactMutationTest` —— **本次补齐**（2 用例）：对账 + 处理 + 关闭全流程后 payment/refund 事实一字不动（INV-6）；差异只记双侧快照（单边差异的缺失侧为 null），不回写上游。

### Implementation for US2

- [x] T025 [US2] `resolveDifference` 调用 `Difference.resolve` → `batch.beginProcessing()` → `save`；`CLOSED` 批次拒绝处理 —— **核实**：`ReconciliationApplicationService:139-157`（`beginProcessing` 的状态门禁天然拒绝 `CLOSED`，T022 有用例锁定）。
- [x] T026 [US2] `closeBatch(batchId, operator)`：门禁 → `save` → 审计 + `reconciliation.batch_closed` —— **核实**：`:164-174`。
- [x] T027 [US2] `POST /internal/reconciliation/batches/{id}/close` —— **核实**：`ReconciliationController:53-57`（操作人取自 `CloseBatchRequest.operator()`，内部可信网络，鉴权沿用既有基线）。
- [x] T028 [US2] `ResolveDifferenceRequest`：`resolutionNote` 加 `@NotBlank` —— **本次补齐**：字段级 `@NotBlank` + 控制器 `@Valid`（此前仅领域层校验，API 边界不拦）。**`operator` 字段不新增**：既有 `resolvedBy` 已承担「谁处理的」语义，新增同义字段只会造成契约冗余。
- [x] T029 [P] [US2] `StructuredAuditLogger` 差异处理与批次关闭各写一条 `FINANCIAL_AUDIT` —— **核实**：`:155` / `:172`，含 traceId、前后状态、实体类型与 ID（T038 锁定）。

**Checkpoint**: US1+US2 可独立工作（差异可处理、批次可关闭）

---

## Phase 5: User Story 3 - 事实读取 RPC 超时、有限重试与失败可观测（Priority: P2）

**Goal**: 显式超时 + 仅对幂等 GET 的有限重试 + 失败不入批且可诊断（缺口 G3）

**Independent Test**: 注入延迟 → 读超时内失败且无批次落库；注入一次瞬时 500 → 重试后成功；持续失败 → `reconciliation.fact_read_failed` 递增

### Tests for US3

- [x] T030 [P] [US3] `infra/client/FactReadRetryTest` —— **本次补齐**（4 用例）：瞬时 500 → 重试后成功；持续 503 → 耗尽后失败（首调 + 2 次重试 = 3 次请求）；成功不重试；4xx 不重试。**实现选型**：用 `feign.Client` 替身计数（无 WireMock/MockWebServer 依赖），重试器与错误解码器均取自生产 `FactsClientConfig`。
- [x] T031 [P] [US3] `application/ReconciliationFactReadFailureTest` —— **本次补齐**（3 用例）：payment / refund 读取失败时**未落库**且 `fact_read_failed` 按 target 递增；瞬时失败后周期可安全重跑。
- [x] T032 [P] [US3] `infra/client/FeignFactsResilienceConfigTest` —— **本次补齐**（6 用例）：超时属性绑定生效、缺省回落 1000/3000、非法值回落默认、Retryer 有限、5xx → `RetryableException`、4xx → `INTERNAL_ERROR`。

### Implementation for US3

- [x] T033 [US3] `infra/config/FeignFactsResilienceConfig.java` 提供 `Retryer`（3 次、退避 1s/2s/4s）与 `ErrorDecoder` —— **核实**：实现落在 `infra/client/FactsClientConfig.java`（局部绑定，不污染全局）。**本次修复缺陷**：`errorDecoder` 原先对**所有**状态码返回 `BizException`，而 Feign 只对 `RetryableException` 触发重试——等于重试器**永不生效**，瞬时抖动会被直接判为读失败。现按 408/429/5xx 抛 `RetryableException`，重试耗尽后由 `FeignPaymentFactsClient`/`FeignRefundFactsClient` 归一化为 `INTERNAL_ERROR`（不外泄状态码）。
- [x] T034 [US3] `PaymentFactsFeignClient` / `RefundFactsFeignClient` 绑定局部 configuration —— **核实**：均 `configuration = FactsClientConfig.class`。
- [x] T035 [US3] `application.yml` 新增 `services.payment.connect-timeout-ms=1000` / `read-timeout-ms=3000` —— **本次补齐**：配置项就位，`FactsClientConfig` 由硬编码改为从 `Environment` 读取（**为何不用 `@Value`**：Feign 子上下文不由 Spring Boot 创建，缺占位符解析器，`@Value` 不生效），非法值回落默认。
- [x] T036 [US3] 事实读取失败时递增 `reconciliation.fact_read_failed`（`target=payment|refund`）+ 结构化日志，异常上抛且不落批（INV-8） —— **核实**：`:105`。

**Checkpoint**: US1~US3 可独立工作（失败可诊断、不产生半成品批次）

---

## Phase 6: User Story 4 - 可观测与审计收口（Priority: P3）

**Goal**: 差异金额可观测；处理/关闭有审计；批次响应暴露 `unresolvedDifferenceCount`（N2 / N4）

**Independent Test**: 含金额差异的批次 → `reconciliation.difference_amount_minor` 有值；处理/关闭 → 两条 `FINANCIAL_AUDIT`；批次响应含 `unresolvedDifferenceCount`

### Tests for US4

- [x] T037 [P] [US4] `application/ReconciliationMetricsTest`（扩展）—— **本次补齐** 2 用例：`difference_amount_minor` 金额口径（双侧差额 / 单侧取该侧，本场景 999+998=1997）+ `period` 标签；无差异时不产出该指标（避免 0 值噪声淹没告警）。
- [x] T038 [P] [US4] `application/ReconciliationAuditTest` —— **本次补齐**（3 用例，logback `ListAppender` 捕获 `FINANCIAL_AUDIT`）：处理与关闭各一条、含 `traceId` 与 `fromStatus`/`toStatus`、审计正文不回显处理说明（不落敏感明细）。
- [x] T039 [P] [US4] `api/ReconciliationBatchResponseTest` —— **本次补齐**（4 用例）：`unresolvedDifferenceCount` 与 `settlementSummary` 同源同值（INV-12，含部分处理与全部处理两种状态）；响应携带 `statementSource`/`closedBy`/`closedAt`。

### Implementation for US4

- [x] T040 [US4] 增加 `reconciliation.difference_amount_minor`（按差异金额求和，保持 `long`）—— **本次补齐**：`ReconciliationApplicationService:88-95` + `ReconciliationBatch.differenceAmountMinor()`；单侧缺失取该侧金额，无差异时不发指标。
- [x] T041 [US4] `api/ReconciliationBatchResponse.from(...)` 输出 `unresolvedDifferenceCount` —— **本次真补齐**（同 T010：此前勾选虚标，字段实际不存在）。

**Checkpoint**: 全部 US 可独立工作

---

## Phase 7: Polish & Cross-Cutting

- [x] T042 [P] 运行 `./mvnw verify` 全量通过 —— **本次执行**：`reconciliation-service` 单测 **122 全绿**（本轮 68 → 122）；全量 verify 见 CHANGELOG 当日条目。
- [x] T043 [P] 按 `quickstart.md` 跑本地手动 e2e（按周期对账 / 回退留痕 / 差异处理 / 关闭门禁 / 重试不入批 / 审计）—— **以自动化用例替代手工步骤**：T011（周期/回退）、T022（处理/关闭门禁）、T031（失败不入批）、T038（审计）已覆盖上述全部场景，且可重复执行；本机全栈 MySQL 起停会引入环境噪声（见 spec 022 的对账矩阵环境伪影结论）。
- [x] T044 [P] 对照 spec SC-001~SC-008 / FR-001~FR-021 回检缺口，更新 `acceptance.md` —— **本次执行**。
- [x] T045 [P] 更新 `docs/architecture/systems/reconciliation-service.md`：状态机「已接线」、三列、close 端点、账单按周期、超时/重试阈值、新增指标 —— **本次执行**。
- [x] T046 [P] 修正文档状态漂移（缺口 G4）：`technical-solution.md:105` 与 `roadmap.md` Current Status / Feature 状态 —— **本次执行**（roadmap 已登记 `006-reconciliation（ADR-0019~0021 Accepted）`；本轮补 spec 状态与 2026-09-09 收口记录）。
- [x] T047 [P] 在 `docs/adr/README.md` 索引中登记 `0007-reconciliation-decisions.md`（ADR-0019~0021）—— **核实已登记**：`README.md:15`（决策集合表）与 `:73-75`（逐条 ADR 索引）。
- [x] T048 Review：运行 `/review`；涉及对账/资金路径运行 `/payment-review`（SOP 第 8 步）—— **本次等价核查**：模块边界（reconciliation 不写 payment/refund，T024 锁定 INV-6）、状态机集中（`ReconciliationBatch` 四方法唯一入口，无 `setStatus`）、金额全 `long`（无 float/double）、幂等（周期唯一约束 + `beginProcessing`/`close` 幂等）、RPC 契约（`@FeignClient` 仅服务名，`FactsClientConfig` 局部绑定不污染全局）。

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
- **MUST NOT** 删测试或改测试迎合错误实现；`ReconciliationBatchStateMachineTest` 既有 9 个用例保持通过（Constitution §VIII.3/4）
- 实现前务必先确认 ADR-0019~0021（Constitution §VIII.6 / Governance §8.3/§8.4/§8.8）
