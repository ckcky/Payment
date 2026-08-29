# Feature Specification: Reconciliation 对账（Mock/预置渠道账单核对与差异处理闭环）

**Feature Branch**: `006-reconciliation`

**Created**: 2026-08-29

**Status**: Draft（设计决策见 `docs/adr/0007-reconciliation-decisions.md`，ADR-0019~0021 待负责人决策）

**Input**: 用户描述：为 Roadmap Phase 6 · Reconciliation 建立 Spec Kit 产物。本 Feature **不是从零构建**——`reconciliation-service`（端口 8088，Schema `reconciliation`）核心比对链路已实现，本 Spec 是**缺口补齐 / 收口**型 Spec。

> 本 Feature 对应 `docs/architecture/roadmap.md` 的 **Phase 6 · Reconciliation（Roadmap 阶段标签「004 Reconciliation」）**，但本仓库 `init-options.json` 规定 spec 目录采用**顺序编号**，故物理目录为 `006-reconciliation`，与 Roadmap 阶段标签**解耦**（同 `003-payment-reliability`、`004-ledger`、`005-refund` 既定约定，见 Clarifications）。
> 所有开放性设计分歧点已落到 ADR-0019~0021（状态 **Proposed**，供负责人按 Constitution §8 确认）。实现前 MUST 先确认这 3 条 ADR。

## 当前代码现实（已核实，禁止按绿地项目理解）

**`reconciliation-service` 已远超「骨架」**：`technical-solution.md:105` 仍标注为「骨架」，但实测代码已落地——领域聚合与状态机（`ReconciliationBatch`/`ReconciliationStatus`）、纯函数匹配（`ReconciliationMatching`）、四类差异（`DifferenceType`）、MyBatis 持久化（JSON 内嵌匹配/差异 + 乐观锁）、按周期幂等（DB 唯一约束 `uk_reconciliation_batches_period` + `DuplicateKeyException` 回查）、差异处理（`Difference.resolve`）、结算汇总（`settlementSummary`，被 `settlement-service` 消费）、出站只读 RPC（payment / refund）、以及单元测试与集成测试。详见 `docs/architecture/systems/reconciliation-service.md`。

**因此本 Spec 的范围是「补缺口」，不是「建服务」。四项已核实的真实缺口：**

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| G1 | **差异处理生命周期不可达**：`beginProcessing()` / `close()` 已在领域层定义（`ReconciliationBatch.java:66/72`）但应用层**从未调用**——`resolveDifference` 只写 `resolutionStatus` 就返回（`ReconciliationApplicationService.java:104-115`），批次永久停在 `HAS_DIFFERENCE` | `ReconciliationApplicationService.java:104-115`、`ReconciliationController.java:46`（无 close 端点） | `technical-solution.md:174` 与 `reconciliation-service.md:64` 记载的「有差异 → 处理中 → 关闭」生命周期**在代码中不可达** |
| G2 | **渠道账单加载忽略 `period`**：`CsvChannelStatementLoader.load(period)` 恒读 `fixtures/channel-statements/sample.csv`（`:24/:27`），参数被丢弃 | `CsvChannelStatementLoader.java:24-29` | 「按周期对账」不成立：任何 period 都比对同一份固定账单，差异不可复现于不同周期 |
| G3 | **事实读取 RPC 无任何弹性配置**：两个 Feign 客户端只有 `name`/`url`，`application.yml` 无 connect/read timeout、无 Retryer、无 ErrorDecoder、无熔断 | `PaymentFactsFeignClient.java:11`、`RefundFactsFeignClient.java:11`、`reconciliation-service/src/main/resources/application.yml:22-26` | 违反 Constitution §V.6「所有外部调用 MUST 有超时」；payment/refund 抖动时无诊断信号 |
| G4 | **文档状态漂移**：`technical-solution.md:105` 标「骨架」，`roadmap.md:11` 称对账「已落地并接入指标」；`reconciliation-service.md:75/216` 已诚实标注 G1/G2 | `technical-solution.md:105`、`roadmap.md:11` | 读者无法判断真实成熟度；与 Constitution §I.3 工程完整要求不符 |

**另发现（超出既定 4 项，已在 Clarifications / ADR 记录）**：

| # | 新发现 | 代码证据 | 处置 |
|---|---|---|---|
| N1 | **平台侧事实同样无周期过滤**：`PaymentFactsService.confirmedFacts()` 返回**全量** `SUCCEEDED` 支付（`PaymentFactsService.java:28-32`），refund 侧同理（`RefundFactsService.java:26-30`）；端点无 `period` 参数 | `PaymentFactsService.java:28`、`RefundFactsService.java:26` | 「按周期对账」在**双侧**都不成立；口径决策见 **ADR-0020** |
| N2 | **对账侧零资金审计**：全服务无 `StructuredAuditLogger` / `FINANCIAL_AUDIT` 使用，仅两个 Micrometer 计数器（`reconciliation.run`、`reconciliation.difference`） | `ReconciliationApplicationService.java:77-81`；`grep FINANCIAL_AUDIT reconciliation-service/src/main` 无命中 | Constitution Observability §2「资金动作 MUST 有审计日志」在差异处理/批次关闭上未落地 |
| N3 | **差异处理依据不完整**：`resolutionNote` 无必填校验，无处理人（`resolvedBy`）与处理时间（`resolvedAt`） | `Difference.java:56-59`、`ResolveDifferenceRequest.java:6` | Roadmap「人工处理依据」仅部分成立 |
| N4 | **批次响应不含未处理差异数**：`ReconciliationBatchResponse` 只有 `matchCount`/`differenceCount`，而 `SettlementEligibility.java:33` 恰以 `unresolvedDifferenceCount > 0` 拒绝结算 | `ReconciliationBatchResponse.java:36-47`、`SettlementEligibility.java:29-37` | 运维无法从批次接口判断该周期可否结算 |
| N5 | **重复 reference 被静默折叠**：`ReconciliationMatching.indexPlatform/indexChannel` 用 `Map.put` 索引，同 reference 后写覆盖前写（`:54-72`） | `ReconciliationMatching.java:54-72` | 与 Roadmap「对账是发现…重复…的关键控制点」冲突；需新增差异类型，列 **[待定]**（见 Clarifications） |

### 能力现状矩阵（诚实标注，禁止按绿地项目理解）

| 能力 | 状态 | 证据 / 说明 |
|---|---|---|
| 领域聚合与状态机（6 态、`start`/`finish`/`beginProcessing`/`close`） | 已实现（后半段未接线） | `domain/ReconciliationBatch.java:50-85` |
| 纯函数逐笔匹配（`Match` + 4 类差异） | 已实现 | `domain/ReconciliationMatching.java:18-52` |
| 平台事实只读 RPC（payment / refund `confirmed-facts`） | 已实现（无周期过滤、无弹性配置） | `infra/client/Feign{Payment,Refund}FactsClient.java` |
| 渠道账单 CSV 加载 | 已实现（**忽略 period**，缺口 G2） | `infra/CsvChannelStatementLoader.java:27` |
| 按周期幂等（唯一约束 + 回查 + 重复键捕获） | 已实现 | `uk_reconciliation_batches_period`、`ReconciliationApplicationService.java:61/86-94` |
| 差异处理 `resolve`（写 `resolutionStatus`/`resolutionNote`） | 已实现（依据不完整，N3） | `Difference.java:56`、`ReconciliationApplicationService.java:104` |
| 差异查询 / 批次查询 | 已实现 | `ReconciliationController.java:34-44` |
| 结算汇总 `settlement-summary`（被 settlement 消费） | 已实现 | `ReconciliationApplicationService.java:117-132`、`SettlementApplicationService.java:62-65` |
| 指标（`reconciliation.run` / `reconciliation.difference`） | 已实现 | `ReconciliationApplicationService.java:77-81` |
| 乐观锁（version，`updateById` 0 行 → `CONFLICT`） | 已实现 | `MybatisReconciliationRepository.java:76-80` |
| 单元 + 集成测试（5 个测试类） | 已实现 | `reconciliation-service/src/test/...` |
| **差异处理 → 处理中 → 关闭 生命周期** | **缺口 G1** | 领域方法存在，应用层零调用 |
| **按周期渠道账单来源** | **缺口 G2（+ N1 平台侧周期口径）** | `load(period)` 忽略参数 |
| **事实读取 RPC 超时 / 有限重试** | **缺口 G3 / [目标]** | 沿用 OpenFeign 默认，无 Retryer |
| **差异处理与批次关闭的 `FINANCIAL_AUDIT`** | **缺口 N2 / [目标]** | 全服务无审计调用 |
| **批次响应暴露 `unresolvedDifferenceCount`** | **[目标]（N4）** | 响应 DTO 无该字段 |
| 熔断 / 降级（Resilience4j） | **[Phase 按需延后]** | `technical-solution.md §4.1` 明示「延迟引入」，Constitution §IV 基础设施门槛 |
| 真实渠道账单接入、自动调账、真实资金修正 | **[Phase 延后]** | Roadmap Phase 6「不包含」 |

## User Scenarios & Testing

> 标注约定：无标记 = 已实现；`[目标]` = 建议值待确认；`[待定]` = 留待后续；`[P1/P2/P3]` = 优先级。

### User Story 1 - 按周期对账，差异可重复识别、可查询、可解释 (Priority: P1)

作为平台资金运营，我希望对某个对账周期执行一次对账后，得到**该周期**平台事实与渠道账单的逐笔比对结果（一致、金额差异、状态差异、平台独有、渠道独有），且同一周期重复执行返回同一份结果（不重复比对、不改写），差异可按批次查询并看到**两侧的金额与状态**，从而让「差异可重复识别、可查询」成立。

**Why this priority**: 这是 Roadmap Phase 6 验收标准的第一条。当前匹配与幂等**已实现**，但 `CsvChannelStatementLoader` 忽略 `period`（G2），且平台侧事实也不带周期（N1）——「按周期对账」在双侧都不成立，差异无法在不同周期间复现与对比，学习闭环价值大打折扣。

**Independent Test**: 准备两份不同内容的渠道账单 fixture（对应 `period=2026-08` 与 `2026-09`），分别对两个周期执行对账，断言两份批次结果的匹配/差异集合**不同**且各自稳定；对 `2026-08` 重复执行 3 次，断言返回同一 `batchId`、差异集合完全一致；查询差异列表，断言每条含 `reference`/`type`/双侧金额/双侧状态/处理状态。

**Acceptance Scenarios**:

1. **Given** 渠道账单存在该周期对应的 fixture，**When** 对 `period=2026-08` 执行对账，**Then** 加载**该周期**的账单条目（而非全局 fixture），比对结果落 `CONSISTENT` 或 `HAS_DIFFERENCE`。
2. **Given** 该周期无专属 fixture（回退场景），**When** 执行对账，**Then** 使用默认全量 fixture 并在批次上记录**实际使用的账单来源**，同时递增 `reconciliation.statement_fallback` 指标 + WARN 日志，**绝不静默**（禁止「看起来按周期对账」）。
3. **Given** 同一周期已被对账过，**When** 再次执行，**Then** 返回首次批次（`uk_reconciliation_batches_period` 兜底），不重复跑匹配、不覆盖已处理的差异。
4. **Given** 一个含四类差异的批次，**When** 查询 `GET /internal/reconciliation/batches/{id}/differences`，**Then** 每条差异含 `reference`、`type`、平台侧与渠道侧的 `amountMinor`/`status`、`resolutionStatus`/`resolutionNote`。
5. **Given** 平台事实中存在某笔而渠道账单没有，**When** 比对，**Then** 产出 `PLATFORM_ONLY` 差异（漏单信号）；反之产出 `CHANNEL_ONLY`（渠道独有信号）；两侧金额不等产出 `AMOUNT_MISMATCH`；金额相等但状态不等产出 `STATUS_MISMATCH`。
6. **Given** 任何对账执行或差异处理，**When** 落库，**Then** **零**对原始 Payment/Refund 的写路径（仅 `GET confirmed-facts`），原始事实不被静默改写。
7. **Given** 一个周期的对账结果，**When** 拉取 `settlement-summary`，**Then** `facts` 只含一致匹配，`unresolvedDifferenceCount` = 未处理差异条数（与 `SettlementEligibility` 口径一致）。

---

### User Story 2 - 差异处理推进批次生命周期，全部处理后方可关闭 (Priority: P1)

作为平台资金运营，我希望处理差异（填写处理依据）后，批次能据此从「有差异」推进到「处理中」，并在**全部差异处理完毕**后由显式动作关闭；关闭后批次成为只读终态，未处理完的差异不允许关闭，从而让「差异可处理」与「可关闭」形成可审计的闭环。

**Why this priority**: 这是本 Feature 唯一**结构性缺失**的能力（G1）。`beginProcessing()`/`close()` 已定义却零调用，意味着：批次永远停在 `HAS_DIFFERENCE`，「处理中/关闭」在代码中不可达，资金运营无法区分「刚发现差异」与「正在处理/已处理完毕」，也无法用批次状态表达「本周期对账已收口」。

**Independent Test**: 对含 2 条差异的批次处理其中 1 条（带非空处理依据），断言批次状态由 `HAS_DIFFERENCE` 变为 `PROCESSING`；尝试关闭，断言被拒绝（仍有 1 条未处理）；处理第 2 条后关闭，断言状态为 `CLOSED`；再对已关闭批次发起差异处理与重复关闭，断言前者被拒绝、后者幂等吸收。

**Acceptance Scenarios**:

1. **Given** 批次处于 `HAS_DIFFERENCE`，**When** 处理其**首条**差异（携带非空 `resolutionNote`），**Then** 批次推进为 `PROCESSING`（领域唯一入口，幂等：已在 `PROCESSING` 时重复处理不报错）。
2. **Given** 批次仍有未处理差异，**When** 请求关闭，**Then** 被**拒绝**（`UNRESOLVED_DIFFERENCES` 或 `STATE_TRANSITION_VIOLATION`），状态不变，不含糊成功。
3. **Given** 批次全部差异均已处理（`PROCESSING` 且未处理数 = 0），**When** 请求关闭（`POST /internal/reconciliation/batches/{id}/close`），**Then** 推进 `CLOSED`，并记录关闭人/关闭时间。
4. **Given** 批次无差异（`CONSISTENT`），**When** 请求关闭，**Then** 直接 `CLOSED`（无需经过 `PROCESSING`）。
5. **Given** 批次已 `CLOSED`，**When** 再次请求关闭，**Then** 幂等吸收（返回当前 `CLOSED`，不报错）；**When** 请求处理差异，**Then** 被拒绝（`STATE_TRANSITION_VIOLATION`），终态只读。
6. **Given** 处理差异时 `resolutionNote` 为空/空白，**When** 提交，**Then** 被拒绝（`INVALID_ARGUMENT`），不写 `resolutionStatus`（人工处理依据 MUST 非空）。
7. **Given** 差异处理或批次关闭成功，**When** 落库，**Then** 写入 `FINANCIAL_AUDIT`（含 `traceId`、周期、批次 ID、`reference`、处理前后状态、处理人与依据），并递增 `reconciliation.difference_resolved` / `reconciliation.batch_closed`。
8. **Given** 批次处于 `CLOSED`，**When** 结算侧拉取 `settlement-summary`，**Then** `unresolvedDifferenceCount = 0`，与 `SettlementEligibility` 判定一致（对账收口 ⇒ 可结算）。

---

### User Story 3 - 事实读取 RPC 具备超时、有限重试与失败可观测 (Priority: P2)

作为平台 SRE，我希望 reconciliation 读取 payment/refund 已确认事实的 RPC 有**明确超时**、对**只读幂等**调用有**有限退避重试**，且失败时不落半成品批次并留下可诊断的指标与日志，从而满足 Constitution §V.6「所有外部调用 MUST 有超时」与 §V.4「重试 MUST 有退避与上限」。

**Why this priority**: 当前两个 Feign 客户端只有 `name`/`url`（G3），超时沿用框架默认、无重试、无错误归一化。对账是**只读**面（不阻塞主资金链路），因此其风险是「静默失败/长时间挂起」而非资金损失；列 P2 因为正确性（US1/US2）优先于弹性。

**Independent Test**: 将 payment-service 的 `confirmed-facts` 注入 5s 延迟，断言对账在读超时（3s）内失败并抛错、**批次未落库**（可安全重跑）；注入一次瞬时 500 后成功，断言重试生效且最终成功；断言 `reconciliation.fact_read_failed` 计数与含目标服务的 WARN 日志存在。

**Acceptance Scenarios**:

1. **Given** payment 或 refund 事实端点响应超过读超时（默认 3s），**When** 执行对账，**Then** 调用在超时内失败，**不落任何批次**（`reconciliation_batches` 无该周期记录），调用方可安全重试。
2. **Given** 事实端点返回瞬时错误（5xx / 连接重置），**When** 执行对账，**Then** 仅对**只读幂等 GET** 重试，最多 3 次、退避 1s/2s/4s；全部失败则整体失败且**不入批**。
3. **Given** 事实端点持续不可用，**When** 重试耗尽，**Then** 递增 `reconciliation.fact_read_failed`（含 `target=payment|refund`）并打印含 `traceId` 与周期的结构化日志，错误码归一化为 `INTERNAL_ERROR`（或 `DEPENDENCY_UNAVAILABLE`）。
4. **Given** 重试配置生效，**When** 审查依赖，**Then** 不引入 Resilience4j / MQ / 任何新中间件（Constitution §IV 基础设施门槛；`technical-solution.md §4.1` 明示熔断延迟引入）。
5. **Given** 事实读取失败导致批次未落库，**When** 周期幂等检查，**Then** 该周期仍可被重新对账（未落库 ⇒ 无批次 ⇒ 重跑合法）。

---

### User Story 4 - 对账可观测与审计收口 (Priority: P3)

作为平台 SRE / 资金风控，我希望对账的每一个关键动作（执行、差异产出、差异处理、批次关闭、事实读取失败、账单回退）都有业务指标与资金审计，指标覆盖**差异条数与差异金额**，审计可跨服务串联 `traceId`，并能从批次响应直接读出未处理差异数，从而满足 Constitution Observability §1/§2/§4。

**Why this priority**: 指标只有 2 个计数器（N2/N4），无审计、无差异金额、无未处理数暴露。对账属只读审计面，不影响主链路可用性，故列 P3；但 Constitution §VII 要求「对账差异」MUST 有告警信号，因此仍须收口。

**Independent Test**: 触发一个含金额差异的批次，断言 `reconciliation.difference` 带 `type` 维度计数且 `reconciliation.difference_amount_minor` 记录差异金额；处理差异与关闭批次后断言 `FINANCIAL_AUDIT` 两条记录含 `traceId`/处理人/前后状态；断言 `GET /internal/reconciliation/batches/{id}` 返回 `unresolvedDifferenceCount`。

**Acceptance Scenarios**:

1. **Given** 对账产出差异，**When** 落库，**Then** 指标含按 `type` 的条数计数与**差异金额**（`|platformAmountMinor - channelAmountMinor|`，单侧缺失取该侧金额）。
2. **Given** 差异被处理或批次被关闭，**When** 动作成功，**Then** 写入 `FINANCIAL_AUDIT`（周期、批次 ID、reference、前后状态、处理人、依据、`traceId`），不记录敏感数据。
3. **Given** 渠道账单发生回退（无该周期 fixture），**When** 对账执行，**Then** 递增 `reconciliation.statement_fallback` 并 WARN 日志（含 period 与实际使用来源）。
4. **Given** 查询批次，**When** 返回响应，**Then** `ReconciliationBatchResponse` 含 `unresolvedDifferenceCount`，与 `settlement-summary` 口径一致。

---

### Edge Cases

- **同周期并发执行对账**：先 `findByPeriod` 回查，未命中则插入；并发撞 `uk_reconciliation_batches_period` 捕获 `DuplicateKeyException` 后回查返回首次批次（已实现，保持不变）。
- **并发处理同一批次的两条差异**：`version` 乐观锁，`updateById` 0 行命中抛 `CONFLICT`，调用方重试（已实现；新增关闭动作同样受乐观锁保护）。
- **账单 fixture 缺失**：无周期专属 fixture 时按 ADR-0020 回退并留痕；连默认 fixture 也缺失则 `INTERNAL_ERROR`（既有行为，保留）。
- **账单条目格式非法**（列数不足、金额非数字）：跳过或报错 MUST 显式，**MUST NOT** 静默丢弃（静默丢弃 = 人为制造漏单假象）；错误行号计入日志。
- **平台事实 reference 为 null**：`ReconciliationMatching` 当前跳过（`indexPlatform:57`），该笔事实不会进入任何差异——属**静默丢弃**，MUST 至少有计数/日志（Roadmap「漏单」目标）。
- **重复 reference 折叠**（N5）：`Map.put` 后写覆盖前写，重复事实被折叠为一条，不产生「重复」差异；本 Feature 记录为 **[待定]**，不在范围内静默改匹配语义。
- **金额差异为 0 但状态差异**：先判金额、后判状态（既有顺序，保持不变），产出 `STATUS_MISMATCH` 而非 `AMOUNT_MISMATCH`。
- **事实读取失败**：不落半成品批次；周期仍可重跑。
- **已关闭批次的任何写操作**：差异处理被拒、重复关闭幂等吸收、`settlement-summary` 仍可读（只读面不受终态影响）。
- **批次关闭后又有新事实产生**：本 Feature 不重开批次（CLOSED 为终态），新周期另开批次；跨周期补差属 `[待定]`。
- **金额溢出/精度**：全程 `long` 最小货币单位或 `BigDecimal`（明确 scale），**MUST NOT** 出现 `float`/`double`；差异金额用 `Math.abs` 后仍为 `long`。
- **跨服务 traceId 断裂**：事实读取与审计 MUST 透传 `traceId`（`TraceContext`），保证审计可跨服务串联。

## Out of Scope（明确不做）

按 Roadmap Phase 6「不包含」，并在本 Spec 中重申，避免实现期范围蔓延：

- **真实渠道账单接入**（SFTP/API 拉取、渠道格式适配）——`[Phase 延后]`；本 Feature 只做 Mock/预置账单的**按周期来源**。
- **自动调账 / 真实资金修正**：本 Feature 只标记处理状态与依据，不改 Payment/Refund/账本。
- **复杂会计处理**：属 `004-ledger`，不在本 Feature。
- **按时间窗口过滤平台事实**（为 `confirmed-facts` 增加 `period` 参数）：属跨服务契约变更（Constitution §8.4），本 Feature **不做**，口径决策见 ADR-0020。
- **熔断 / 降级（Resilience4j）**：无真实负载证据，Constitution §IV 基础设施门槛未过（`technical-solution.md §4.1` 明示延迟引入）。
- **重复 reference 差异类型**（N5）：需新增差异类型与匹配语义变更，**[待定]**，不在本 Feature 静默引入。
- **对账调度器**（定时触发对账）：当前由运维/测试显式调用 `POST /internal/reconciliation/batches`；进程内调度器属 `[待定]`。
- **多币种清分、分批/分页拉取大账单**：`[Phase 延后]`。

## Requirements

### Functional Requirements

- **FR-001**: 系统 MUST 支持按对账周期（`period`）执行对账，并以数据库唯一约束 `uk_reconciliation_batches_period` 兜底周期幂等：同周期重复执行返回首次批次，不重复比对、不覆盖已处理的差异。
- **FR-002**: 平台事实 MUST 仅经**只读** RPC（`GET /internal/payments/confirmed-facts`、`GET /internal/refunds/confirmed-facts`）获取并落地为 `PlatformFact` 快照；系统 MUST NOT 存在任何回写 Payment/Refund 的代码路径（Constitution §III 边界 #4、technical-solution §4.3.5）。
- **FR-003**: 渠道账单加载 MUST 按 `period` 定位账单来源；命中该周期 fixture 即用之，未命中则回退默认 fixture 并**留痕**（批次记录实际来源 + `reconciliation.statement_fallback` 指标 + WARN 日志），**MUST NOT** 静默回退（缺口 G2，ADR-0020）。
- **FR-004**: 匹配 MUST 保持为**纯函数、确定性**比对（同输入 ⇒ 同输出），按 `reference` 产出一致 `Match` 与四类差异（`AMOUNT_MISMATCH` / `STATUS_MISMATCH` / `PLATFORM_ONLY` / `CHANNEL_ONLY`）。
- **FR-005**: 金额 MUST 一律用最小货币单位 `long` 分或 `BigDecimal`（明确 scale），**MUST NOT** 使用 `float`/`double`；金额比较与差异金额计算 MUST 保持整数语义（Constitution §II.1）。
- **FR-006**: 差异 MUST 可按批次查询，且每条 MUST 暴露 `reference`、`type`、平台侧与渠道侧 `amountMinor`/`status`、`resolutionStatus`、`resolutionNote`。
- **FR-007**: 差异处理 MUST 要求非空 `resolutionNote`（人工处理依据），并 MUST 记录处理人（`resolvedBy`）与处理时间（`resolvedAt`）；重复处理同一差异 MUST 幂等吸收（缺口 N3）。
- **FR-008**: 处理批次中**首条**差异时，批次 MUST 经领域状态机唯一入口从 `HAS_DIFFERENCE` 推进为 `PROCESSING`，且该推进 MUST 幂等（已在 `PROCESSING` 时不报错）（缺口 G1，ADR-0019）。
- **FR-009**: 系统 MUST 提供显式关闭端点 `POST /internal/reconciliation/batches/{id}/close`：`CONSISTENT` 或 `PROCESSING` 可关闭；`HAS_DIFFERENCE` 直接关闭 MUST 被拒绝；关闭前 MUST 校验未处理差异数为 0（缺口 G1，ADR-0019）。
- **FR-010**: `CLOSED` MUST 为只读终态：关闭后处理差异 MUST 被拒绝（`STATE_TRANSITION_VIOLATION`），重复关闭 MUST 幂等吸收；`settlement-summary` 读取不受影响。
- **FR-011**: 差异处理与批次关闭 MUST 写入 `FINANCIAL_AUDIT`（含 `traceId`、period、batchId、reference、前后状态、处理人、依据），并递增 `reconciliation.difference_resolved` / `reconciliation.batch_closed`（Constitution §VII.2，缺口 N2）。
- **FR-012**: 出站事实读取 RPC MUST 显式配置超时（默认 connect 1s / read 3s，可配置），满足 Constitution §V.6（缺口 G3，ADR-0021）。
- **FR-013**: 事实读取 RPC MUST 对**只读幂等 GET** 提供有限重试（默认 3 次、退避 1s/2s/4s，可配置）；**MUST NOT** 对任何写操作重试；重试耗尽后整体失败（ADR-0021）。
- **FR-014**: 事实读取失败 MUST **不落半成品批次**（该周期无批次 ⇒ 可安全重跑），并 MUST 递增 `reconciliation.fact_read_failed`（含 `target` 维度）+ 打印含 `traceId` 与周期的结构化日志；错误码归一化为 `INTERNAL_ERROR`。
- **FR-015**: 本 Feature **MUST NOT** 引入 MQ、跨服务异步事件、2PC/XA 或 Resilience4j 等新中间件/基础设施（Constitution §IV、ADR-0001）。
- **FR-016**: 所有批次状态迁移 MUST 经 `ReconciliationBatch` 唯一入口（`start`/`finish`/`beginProcessing`/`close`），MUST NOT 散落 `setStatus`；并发更新由 `version` 乐观锁保护（Constitution §V.2）。
- **FR-017**: `settlementSummary` MUST 保持「仅一致匹配进结算事实 + `unresolvedDifferenceCount`」语义，与 `SettlementEligibility`（未处理差异 > 0 ⇒ 不可结算）一致；批次响应 MUST 额外暴露 `unresolvedDifferenceCount`（缺口 N4）。
- **FR-018**: 渠道账单条目解析 MUST 显式处理非法行（列数不足/金额非法），**MUST NOT** 静默丢弃；平台事实 `reference` 为 null 时 MUST 至少有可观测痕迹（计数或 WARN 日志）。
- **FR-019**: Database-per-service：reconciliation-service 只读写自有 `reconciliation` Schema，MUST NOT 直接 SQL 他服务表（Constitution §IV.4）。
- **FR-020**: 金额路径与状态机路径 MUST 有单元测试与集成测试（差异金额、周期幂等、生命周期闭合、乐观锁冲突、重试耗尽不入批）；**MUST NOT** 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。
- **FR-021**: 实现完成后 MUST 同步修正文档状态漂移：`technical-solution.md:105` 的「骨架」标注与 `roadmap.md` 的 Current Status（缺口 G4）。

### Key Entities

- **ReconciliationBatch（对账批次聚合根，已实现 + 扩展）**：某周期内平台事实与渠道账单的比对结果，持有状态机。本 Feature 新增/启用 `beginProcessing()`（幂等推进 `PROCESSING`）与 `close()`（幂等关闭 + 未处理差异前置校验），并新增 `statementSource`（本次实际使用的账单来源）、`closedAt`/`closedBy`。位置 `reconciliation-service/.../domain/ReconciliationBatch.java`。
- **Difference（差异实体，已实现 + 扩展）**：单侧或两侧不一致事实。本 Feature 扩展 `resolvedAt`/`resolvedBy`，并强制 `resolutionNote` 非空（人工处理依据）。位置 `.../domain/Difference.java`（JSON 内嵌 `differences_json`，无需新表）。
- **Match（一致匹配值对象，已实现）**：`reference + type + amountMinor + currencyCode`，结算侧直接取金额。位置 `.../domain/Match.java`。
- **PlatformFact（平台事实快照值对象，已实现）**：只读 RPC 拉取的 Payment/Refund 已确认事实副本（`type=PAYMENT/REFUND`），不回写来源。
- **ChannelStatement（渠道账单条目值对象，已实现）**：当前来自本地 Mock/CSV fixture，按周期定位后加载。
- **ChannelStatementSource（账单来源值对象，新增）**：描述本次对账实际使用的账单来源（来源类型 `FIXTURE`、定位符、条目数、是否回退），随批次持久化，支撑「按周期可追溯、回退不静默」（FR-003）。
- **ChannelStatementLoader（出站/基础设施端口，已实现 + 改造）**：`load(period)` 由「固定全局 fixture」改为「按周期定位 + 显式回退」。位置 `.../application/ChannelStatementLoader.java` / `infra/CsvChannelStatementLoader.java`。
- **FactReadResilience（基础设施配置，新增）**：事实读取 Feign 客户端的超时 / Retryer / ErrorDecoder 配置与失败指标，仅作用于只读幂等 GET。位置 `.../infra/client/`、`config/`。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 同一周期重复执行对账 100% 返回首次批次（差异集合完全一致）；不同周期使用不同账单时结果 100% 不同（按周期对账成立）。
- **SC-002**: 账单回退场景 100% 留痕（批次记录实际来源 + 指标 + WARN 日志），0 次静默回退。
- **SC-003**: 四类差异 100% 可被识别与查询，每条含双侧金额/状态/处理状态/处理依据；金额差异与状态差异判定顺序稳定。
- **SC-004**: 差异处理 100% 推进批次生命周期（首条 ⇒ `PROCESSING`，全部处理 ⇒ 可 `CLOSED`）；存在未处理差异时关闭 100% 被拒绝；`CLOSED` 批次的差异处理 100% 被拒绝、重复关闭 100% 幂等吸收。
- **SC-005**: 原始 Payment/Refund 事实 100% 零回写（代码级无写路径，集成测试断言对账前后 Payment/Refund 快照不变）。
- **SC-006**: 事实读取 RPC 100% 按配置超时；瞬时故障 100% 触发有限重试（≤ 3 次、1s/2s/4s）；失败 100% 不入批且 100% 产出 `reconciliation.fact_read_failed`。
- **SC-007**: 差异处理与批次关闭 100% 写入含 `traceId`/处理人/前后状态的 `FINANCIAL_AUDIT`；差异条数与差异金额 100% 可观测；批次响应 100% 暴露 `unresolvedDifferenceCount`。
- **SC-008**: `mvnw verify` 全量通过；无 `float`/`double` 出现在对账金额路径（静态检视）。

## Assumptions

- `reconciliation-service`（8088 / Schema `reconciliation`）核心比对链路已实现，本 Feature 只补缺口与扩展，**不重写**既有匹配算法、持久化与周期幂等机制。
- 不引入 MQ / 分布式事务 / 跨服务异步事件 / 熔断中间件；事实读取为同步 RPC + 幂等 + 有限重试（Constitution §IV、ADR-0001）。
- 当前为单节点/单机部署；对账由运维/测试显式触发，**假定同一周期同一时刻只有一个执行者**（并发由 DB 唯一约束兜底）。
- 渠道账单仍为 Mock/预置 fixture；真实渠道接入不在本 Feature（Roadmap Phase 6「不包含」）。
- 平台侧事实沿用现有「全量已确认事实」端点，本 Feature **不**为其增加 `period` 参数；`period` 的语义口径由 ADR-0020 明确（批次/快照标识，MVP 不做时间窗口过滤）。
- 超时（connect 1s / read 3s）与重试（3 次 / 1s-2s-4s）为建议默认值，可通过配置覆盖；取值在 data-model/quickstart 中给出，实施后按需调优。
- 具体取舍（生命周期接线与关闭语义、账单来源按周期 vs 参数化加载器、事实读取弹性）见 ADR-0019~0021，**实现前 MUST 由负责人确认**（Constitution §8.2/§8.4/§8.8）。

## Dependencies（依赖与前置）

| 依赖 | 状态 | 说明 |
|---|---|---|
| Phase 2/3/5（Payment / Reliability / Refund） | 已完成 | 支付与退款已确认事实是对账的输入；退款侧事实端点已实现 |
| `payment-service` `GET /internal/payments/confirmed-facts` | 已实现（无 period 参数） | 事实读取；本 Feature 只加超时/重试，不改契约 |
| `refund-service` `GET /internal/refunds/confirmed-facts` | 已实现（无 period 参数） | 同上 |
| `settlement-service` 消费 `settlement-summary` | 已实现 | `SettlementEligibility` 以未处理差异数拒绝结算；本 Feature 保持口径一致（FR-017） |
| `deployment/schema/07-reconciliation-schema.sql` | 已实现（需扩展） | 本 Feature 新增 `statement_source` / `closed_at` / `closed_by` 列（Constitution §8.3） |
| `common-core` `StructuredAuditLogger` / `BusinessMetrics` | 已实现（对账侧未用审计） | 本 Feature 接入 `FINANCIAL_AUDIT`（N2） |
| 渠道账单 fixture 目录 | 部分（仅 `sample.csv`） | 本 Feature 增加按周期 fixture 与回退策略（G2） |

## Clarifications

### Session 2026-08-29

- **编号约定**：spec 目录采用顺序编号 `006-reconciliation`，与 Roadmap 阶段标签「004 Reconciliation / Phase 6」**解耦**（Roadmap 标签为阶段描述，非 spec ID；同 `003-payment-reliability`「Roadmap 002」、`004-ledger`「Roadmap 006」、`005-refund`「Roadmap 003」的既定约定）。
- **Spec 性质**：本 Spec 为**缺口补齐型**（gap-closing / completion），非绿地构建。四项缺口 G1~G4 见文首表格，均已核实到 `file:line`；另发现 N1~N5 一并记录。
- **分歧点 → ADR**（`docs/adr/0007-reconciliation-decisions.md`，状态 **Proposed**，待负责人决策）：
  - 批次差异处理生命周期（如何接线 `beginProcessing`/`close`、「处理中/关闭」的语义与门禁）→ **ADR-0019**。
  - 渠道账单来源（按周期 fixture + 显式回退 vs 参数化加载器 vs 维持全局 fixture）→ **ADR-0020**。
  - 事实读取 RPC 的弹性（超时/有限重试/错误归一化 vs 引入熔断中间件）→ **ADR-0021**。
- **新发现的矛盾（未写入 ADR，供负责人知悉）**：
  1. **平台侧事实同样无周期过滤（N1）**：`PaymentFactsService.java:28` 与 `RefundFactsService.java:26` 返回全量已确认事实，端点无 `period` 参数。这意味着「按周期对账」双侧均不成立，仅修 `CsvChannelStatementLoader`（G2）不足以达成 Roadmap 目标。ADR-0020 已就此给出 MVP 口径（period = 批次/快照标识），但**真正的周期窗口过滤需改跨服务契约**，须另立 ADR（Constitution §8.4）。
  2. **对账侧零资金审计（N2）**：`reconciliation-service` 无任何 `StructuredAuditLogger` 使用，与 Constitution §VII.2「资金动作 MUST 有审计日志」存在差距；本 Spec 以 US4/FR-011 补齐差异处理与批次关闭两处审计。
  3. **批次响应与结算资格口径不一致（N4）**：`SettlementEligibility.java:33` 以 `unresolvedDifferenceCount > 0` 拒绝结算，但 `ReconciliationBatchResponse` 不暴露该数（仅有 `differenceCount`），运维需另查 `settlement-summary` 才能判断可否结算。
  4. **重复 reference 被静默折叠（N5）**：`ReconciliationMatching.java:54-72` 用 `Map.put` 索引，同 reference 后写覆盖前写，真实场景中的「重复入账」不会产生差异，与 Roadmap「对账是发现…重复…的关键控制点」冲突。本 Spec 记为 **[待定]**，不在本 Feature 静默改匹配语义（避免 Constitution §VIII.5 擅自变更领域模型）。
  5. **文档成熟度标注不一致（G4 的细节）**：`technical-solution.md:105` 标 reconciliation-service 为「骨架」，而 `roadmap.md:11` 称对账「已落地并接入指标」；`reconciliation-service.md` 本身已诚实标注 G1/G2。三处口径需按 ADR 结论统一（FR-021）。
