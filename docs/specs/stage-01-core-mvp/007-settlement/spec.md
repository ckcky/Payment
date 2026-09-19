# Feature Specification: Settlement 结算（调整项、已确认事实闸门、批次收口与记账）

**Feature Branch**: `007-settlement`

**Created**: 2026-08-29

**Status**: Draft（设计决策见 `docs/adr/0008-settlement-decisions.md`，ADR-0022~0023 待负责人决策）

**Input**: 用户描述：为 Roadmap Phase 7 · Settlement 建立 Spec Kit 产物。本 Feature **不是从零构建**——`settlement-service`（端口 8089，Schema `settlement`）已有**可运行 MVP**，本 Spec 是**缺口补齐 / 收口**型 Spec。

> 本 Feature 对应 `docs/architecture/roadmap.md` 的 **Phase 7 · Settlement（Roadmap 阶段标签「005 Settlement」）**，但本仓库 `init-options.json` 规定 spec 目录采用**顺序编号**，故物理目录为 `007-settlement`，与 Roadmap 阶段标签**解耦**（同 `003-payment-reliability`「Roadmap 002」、`004-ledger`「Roadmap 006」、`005-refund`「Roadmap 003」、`006-reconciliation`「Roadmap 004」的既定约定，见 Clarifications）。
> 所有开放性设计分歧点已落到 ADR-0022~0023（状态 **Proposed**，供负责人按 Constitution §8 确认）。实现前 MUST 先确认这 2 条 ADR。

## 当前代码现实（已核实，禁止按绿地项目理解）

**`settlement-service` 已远超「骨架」**：`technical-solution.md:106` 仍标注为「骨架」，但实测代码已落地——商户资格校验（`SettlementEligibility`）、基于 reconciliation `settlement-summary` 的净额计算、批次与明细持久化（MyBatis-Plus + 乐观锁）、完整八态状态机（`PENDING → CALCULATING → READY → EXECUTING → SUCCEEDED/FAILED/UNKNOWN → CLOSED`）、双唯一约束幂等（幂等键 + 商户+周期）、模拟执行强制进 `UNKNOWN`、`FINANCIAL_AUDIT` 审计与三项 Micrometer 指标、两个出站 Feign 客户端（merchant / reconciliation）以及 5 个测试类。详见 `docs/architecture/systems/settlement-service.md`。

**因此本 Spec 的范围是「补缺口」，不是「建服务」。五项已核实的真实缺口：**

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| G1 | **调整项恒为 0，调整能力未实现**：`createBatch` 硬编码 `batch.calculate(income, refund, 0, "CNY")`；`Adjustment` 值对象在全项目**零引用**（仅定义）；`SettlementItem` 的 `ADJUSTMENT` 类型**从未被构造** | `SettlementApplicationService.java:80`、`:70-77`、`Adjustment.java:6`、`SettlementItem.java:6-8` | Roadmap Phase 7「包含：收入、退款和**调整项**的最小净额计算」中「调整项」一条**不成立**；`settlement-service.md:91` 亦自认「MVP 调整额固定 0」 |
| G2 | **「仅已确认事实可结算」闸门被整体委托**：settlement 只用 reconciliation 返回的 `unresolvedDifferenceCount` 判定，自身**不逐条校验**事实的类型/币种/周期归属；未知 `type` 的事实既不进收入也不进退款，却被 `addItem` 写入明细 | `SettlementApplicationService.java:62-68`、`:70-77`、`:82-84` | Constitution「settlement MUST NOT settle unconfirmed/unknown facts」在 settlement 侧**无运行时强制**，仅是架构假设；且明细与金额可能不一致（明细有条、金额不计） |
| G3 | **多币种字段存在但无任何校验**：`currency_code` 在批次与明细上都有，但批次币种硬编码 `CNY`，事实币种不校验，混币种事实被静默相加 | `SettlementApplicationService.java:79-80`、`SettlementBatchEntity.java:14`、`SettlementItemEntity.java:18` | 金额铁律风险：混币种静默相加＝错账。清分延后合理，但「不静默」应成立 |
| G4 | **settlement → ledger 记账未接线**：settlement 侧无 `LedgerPostingGateway`；而 `ledger-service`（8090）已实现，`Account.SETTLEMENT_PAYABLE(4)`、`LedgerSourceType.SETTLEMENT`、`LedgerEntry.Type.SETTLEMENT` 均已就位；payment 侧网关已落地 | 缺失（对比 `payment-service/.../application/LedgerPostingGateway.java` 与 `infra/client/FeignLedgerPostingGateway.java:21`）；`Account.java:18`、`LedgerSourceType.java:9`、`LedgerEntry.java:102` | `004-ledger` spec US3 的**结算侧**未落地；Constitution §II.3「一切资金变动 MUST 经 ledger-service」在结算链路上悬空 |
| G5 | **文档状态漂移**：`technical-solution.md:106` 标 settlement-service「骨架」；`:101` 仍标 ledger-service「延后 Phase 8」（实际已实现并运行）；`roadmap.md:11` 称「结算服务已落地并接入指标」；`settlement-service.md:91` 写 `adjustment_minor（MVP=0）` | `technical-solution.md:101/106`、`roadmap.md:11`、`settlement-service.md:91` | 读者无法判断真实成熟度；三处口径互相矛盾（Constitution §I.3 工程完整要求） |

**另发现（超出既定 5 项，已在 Clarifications / ADR 记录）**：

| # | 新发现 | 代码证据 | 处置 |
|---|---|---|---|
| N1 | **事实无商户维度 ⇒ 跨商户串账**：`PlatformFact` / `ReconciliationSettlementFact` / `SettlementFact` 均无 `merchantId`；`createBatch` 把该周期的**全部**事实算入任一商户的批次，而 `(merchant_id, period)` 唯一约束允许商户 B 生成一份含商户 A 事实的批次 | `PlatformFact.java:13`、`ReconciliationSettlementFact.java:7-8`、`SettlementFact.java:6`、`SettlementApplicationService.java:70-77/82-84` | 属**跨服务契约变更**（Constitution §8.4），本 Spec **不静默改契约**；以 FR-007/FR-022 要求「事实条数与来源周期入批次、混/串不静默」，归属见 **ADR-0023** |
| N2 | **周期无对账批次 ⇒ 未归一化异常**：`settlementSummary` 无批次时抛 `NOT_FOUND`，settlement 侧 `FeignReconciliationClient` 未做 404 → `BizException` 归一化，异常冒泡为 500/`INTERNAL_ERROR` | `ReconciliationApplicationService.java:118-120`、`FeignReconciliationClient.java:23-29` | 「该周期没对账 ⇒ 不能结算」的语义丢失；FR-009 收口 |
| N3 | **`close()` 零调用（与 reconciliation G1 同型）**：领域定义了 `close()`，但应用层零调用、控制器无 close 端点，批次**永远到不了 `CLOSED`** | `SettlementBatch.java:98-104`、`SettlementController.java:25-41` | 状态机后半段不可达；FR-014 收口 |
| N4 | **出站 Feign 无超时配置**：两个客户端只有 `name`/`url`，`application.yml` 无 `feign` 段 | `MerchantFeignClient.java:10`、`ReconciliationFeignClient.java:10`、`settlement-service/src/main/resources/application.yml:22-26` | 违反 Constitution §V.6「所有外部调用 MUST 有超时」；`settlement-service.md:257` 自标 `[目标]` connect 1s / read 3s；FR-019 收口 |
| N5 | **幂等键跨商户/周期错配被静默**：按幂等键命中即返回，**不校验** `merchantId`/`period` 与本次请求一致 | `SettlementApplicationService.java:49-52` | 复用错键会静默返回他商户/他周期批次（资金错配）；FR-012 收口 |
| N6 | **结果收敛无人工依据**：`ResolveSettlementRequest` 只有 `status`，无操作人/理由；`FINANCIAL_AUDIT` 也只记状态迁移 | `ResolveSettlementRequest.java:6`、`SettlementApplicationService.java:117-121` | 与 `006-reconciliation` FR-007「人工处理依据 MUST 非空」口径不一致；FR-016 收口 |

### 能力现状矩阵（诚实标注，禁止按绿地项目理解）

| 能力 | 状态 | 证据 / 说明 |
|---|---|---|
| 商户结算资格校验（ACTIVE + settlementEligible + 无未解决差异） | 已实现 | `SettlementEligibility.java:19-27`、`SettlementApplicationService.java:59-68` |
| 净额计算（收入 − 退款；不含调整） | 已实现（调整项缺失，G1） | `SettlementBatch.compute:47-59`、`SettlementApplicationService.java:70-80` |
| 批次 + 明细持久化（本地事务 + 乐观锁） | 已实现 | `MybatisSettlementRepository.java:53-81` |
| 八态状态机（唯一转换入口 + 终态吸收） | 已实现（`close` 未接线，N3） | `SettlementBatch.java:61-145` |
| 双唯一约束幂等（幂等键 + 商户+周期）+ 重复键回查 | 已实现（跨商户/周期错配未校验，N5） | `SettlementApplicationService.java:49-57/134-143`、`08-settlement-schema.sql:25-26` |
| 模拟执行强制进 `UNKNOWN`（无真实出款） | 已实现 | `SettlementApplicationService.java:93-96` |
| 未知结果收敛 `resolveBatch` | 已实现（无操作人/理由，N6） | `SettlementApplicationService.java:110-127` |
| 批次查询（按 id） | 已实现（无按商户+周期查询） | `SettlementController.java:32-35` |
| 指标 `settlement.batch_initiated/.unknown/.failed` + `FINANCIAL_AUDIT` | 已实现（闸门拒绝无指标，G2） | `SettlementApplicationService.java:88-101` |
| 单元 + 状态机 + 资格测试（5 个测试类） | 已实现 | `settlement-service/src/test/...` |
| **调整项（Adjustment）参与净额** | **缺口 G1** | `Adjustment.java:6` 零引用；`calculate(..., 0, ...)` |
| **「仅已确认事实」的本地强制校验** | **缺口 G2 / [目标]** | 逐条事实不校验 type/币种/周期 |
| **`close()` 端点与按商户+周期查询** | **缺口 N3 / [目标]** | 领域方法存在，应用层零调用 |
| **出站 Feign 超时与有限重试** | **缺口 N4 / [目标]** | 沿用 OpenFeign 默认，无 Retryer |
| **settlement → ledger-service 记账** | **缺口 G4 / [待定]** | 依赖 ADR-0023 与 Roadmap Phase 7「不实现 Ledger」的取舍 |
| 多币种清分、税费、复杂分账、真实出款 | **[Phase 延后]** | Roadmap Phase 7「不包含」 |

## User Scenarios & Testing

> 标注约定：无标记 = 已实现；`[目标]` = 建议值待确认；`[待定]` = 留待后续；`[P1/P2/P3]` = 优先级。

### User Story 1 - 调整项真实进入净额计算，且登记可追溯、可审计 (Priority: P1)

作为平台资金运营，我希望能够为一个商户的某个结算周期**登记调整项**（如平台赔付补差、手续费返还、客诉扣款），并让这些调整项在生成结算批次时**真实参与净额计算**、在批次明细中以 `ADJUSTMENT` 类型逐条可查、且每次登记都留下操作人、理由与审计，从而让 Roadmap「收入、退款和**调整项**的最小净额计算」真正成立。

**Why this priority**: 这是 Roadmap Phase 7「包含的 Feature」中唯一**完全没有实现**的一条。当前 `adjustment` 恒为 0，`Adjustment` 值对象零引用、`ADJUSTMENT` 明细类型从未构造——净额实际只有「收入 − 退款」两项，任何补差/扣款都无处表达，结算金额在现实场景中不可用。

**Independent Test**: 为商户 `M1` 周期 `2026-08` 登记 2 条调整项（补差 `+500`、扣款 `-300`，均带非空理由与操作人、各自幂等键），再对该商户周期创建结算批次；断言 `adjustmentMinor = +200`、`netMinor = income − refund + 200`，批次含 2 条 `ADJUSTMENT` 明细且其带符号求和等于 `adjustmentMinor`；重复登记同一幂等键断言返回首次调整项且不重复计入；在批次已存在后登记第 3 条调整项，断言被拒绝且批次净额不变。

**Acceptance Scenarios**:

1. **Given** 商户 `M1`/周期 `2026-08` 尚无结算批次，**When** 登记一条调整项（`amountMinor=500`、方向 `CREDIT`（补差，增加净额）、`reason="平台赔付补差"`、`operator="ops-1"`、`idempotencyKey="adj-1"`），**Then** 调整项落库为 `ACTIVE`，写一条 `FINANCIAL_AUDIT`（含 `traceId`/操作人/理由/金额/方向），并递增 `settlement.adjustment_registered`。
2. **Given** 同一幂等键 `adj-1` 被重复提交（参数相同），**When** 登记，**Then** 返回首次调整项（`uk_settlement_adjustments_idem` 兜底），不产生第二条、金额不被重复计入。
3. **Given** 同一幂等键携带**不同**参数（金额/方向/周期不同），**When** 登记，**Then** 被拒绝（`DUPLICATE`），**MUST NOT** 静默覆盖首次登记（Constitution §V.1）。
4. **Given** 已登记补差 `+500` 与扣款 `-300`，**When** 创建该商户周期的结算批次，**Then** `adjustmentMinor = +200`，`netMinor = incomeMinor − refundMinor + 200`，且批次含 2 条 `ADJUSTMENT` 明细（reference 为调整项业务编号），带符号求和 = `+200`。
5. **Given** 批次已生成（`UNKNOWN` 或更晚状态），**When** 再为该 `(merchant, period)` 登记调整项，**Then** 被拒绝（`STATE_TRANSITION_VIOLATION`，批次净额与创建时快照一致），并递增 `settlement.adjustment_rejected{reason=batch_exists}`。
6. **Given** 登记请求 `reason` 为空/空白或 `operator` 为空，**When** 提交，**Then** 被拒绝（`INVALID_ARGUMENT`），不落库（人工调整依据 MUST 非空，与 `006-reconciliation` FR-007 同口径）。
7. **Given** 调整项币种与批次币种不一致（MVP 仅 `CNY`），**When** 登记或建批，**Then** 被拒绝（`AMOUNT_INVARIANT_VIOLATION`），**MUST NOT** 静默混币种相加。

---

### User Story 2 - 「未确认/未知事实不得结算」闸门在 settlement 本地可执行 (Priority: P2)

作为平台资金风控，我希望 settlement 在生成批次前**逐条校验**它即将结算的每一条财务事实：类型必须是已知类型、币种必须与批次币种一致、金额必须非负、事实所属周期必须与请求周期一致；任何一条不满足就**拒绝建批并留痕**，绝不静默忽略、绝不静默混算，从而让 Constitution「settlement MUST NOT settle unconfirmed/unknown facts」从「架构假设」变成「运行时强制」。

**Why this priority**: 当前闸门完全委托给 reconciliation 的一个计数（G2）。这意味着：reconciliation 的口径一旦变化、或事实类型出现约定外取值、或混入其他币种，settlement 会**静默**产出错误净额（N1/G3/N2 相关）。该缺口不修，「未确认事实不能结算」这条 Roadmap 验收标准只在 happy path 上成立。列 P2 是因为既有闸门（未解决差异计数）已挡住最常见风险，本 Story 是纵深防御与「不静默」。

**Independent Test**: 构造一份 `settlement-summary`，含 ① 一条 `type="PAYMENT"` 正常事实、② 一条 `type="FEE"`（约定外类型）、③ 一条 `currencyCode="USD"` 的事实、④ 一条 `amountMinor=-100` 的事实；分别单独建批，断言 ②③④ 各被拒绝（`INVALID_ARGUMENT` / `AMOUNT_INVARIANT_VIOLATION`）、`settlement_batches` 中该商户周期**无批次**、指标 `settlement.gate_rejected{reason=...}` 递增且日志含 `traceId`；对 ① 建批成功并断言明细与金额合计一致。另：对未对账过的周期建批，断言返回 `NOT_FOUND`（非 500）。

**Acceptance Scenarios**:

1. **Given** 汇总中存在 `type` 不属于 `{PAYMENT, REFUND}` 的事实，**When** 创建批次，**Then** 被拒绝（`INVALID_ARGUMENT`），**MUST NOT** 被静默忽略（当前行为：进明细却不进金额，见 G2）。
2. **Given** 汇总中存在 `currencyCode` 与批次币种（`CNY`）不一致的事实，**When** 创建批次，**Then** 被拒绝（`AMOUNT_INVARIANT_VIOLATION`），**MUST NOT** 静默相加。
3. **Given** 汇总中存在 `amountMinor < 0` 的事实，**When** 创建批次，**Then** 被拒绝（`AMOUNT_INVARIANT_VIOLATION`）。
4. **Given** `settlement-summary` 返回的 `period` 与请求 `period` 不一致，**When** 创建批次，**Then** 被拒绝（`INVALID_ARGUMENT`），并留下含周期与 `traceId` 的 WARN 日志（来源周期证据）。
5. **Given** 该周期在 reconciliation 中**不存在**对账批次（404），**When** 创建批次，**Then** 归一化为 `NOT_FOUND`（「无对账 ⇒ 不可结算」），**MUST NOT** 冒泡为未处理的 Feign 异常（N2）。
6. **Given** 闸门拒绝，**When** 落库检查，**Then** `settlement_batches` 中该商户周期**无记录**（不落半成品批次），指标 `settlement.gate_rejected{reason=...}` 递增。
7. **Given** 建批成功，**When** 校验批次不变量，**Then** `sum(PAYMENT 明细) = incomeMinor`、`sum(REFUND 明细) = refundMinor`、`sum(ADJUSTMENT 明细，带符号) = adjustmentMinor`，且明细条数 = 参与计算的事实条数 + 调整项条数（**零**「在明细里但不在金额里」的事实）。
8. **Given** 任意结算动作，**When** 检查依赖方向，**Then** settlement 对 reconciliation / payment / refund **零写路径**（仅 `GET settlement-summary`，已实现并保持不变）。

---

### User Story 3 - 结算批次结果可查询、可收敛、可关闭，并在确认成功时向账本记账 (Priority: P3)

作为平台资金运营与账务，我希望：一个批次在模拟执行后进入 `UNKNOWN`，能被**按 id 或按商户+周期查询**到全部金额与状态；携带**操作人与理由**被收敛为 `SUCCEEDED`/`FAILED`；终态后可被**显式关闭**（幂等）；并且（若 ADR-0023 采纳）在收敛为 `SUCCEEDED` 时经同步 RPC 向 `ledger-service` 记一笔「借 商户应付 / 贷 结算应付」的平衡分录、幂等键 `SETTLEMENT:<batchId>`，记账失败**不回滚**批次状态而是进入待记账兜底，从而让「结算结果可查询、未知结果可收敛、账务可追溯」形成闭环。

**Why this priority**: 查询与收敛已实现（缺操作人/理由与 close 端点，N3/N6）；而 settlement → ledger 记账同时涉及 Roadmap Phase 7「**不实现 Ledger**」与 Constitution §II.3「一切资金变动 MUST 经 ledger-service」（ledger-service 现已落地）的取舍，且属 `004-ledger` US3 的结算侧，必须经 ADR 确认归属与时机，故列 P3 并标注 `[待定]`。

**Independent Test**: 建批后按 `(merchantId, period)` 查询断言命中同一 `batchId` 且状态为 `UNKNOWN`；以 `operator`/`reason` 收敛为 `SUCCEEDED`，断言审计含操作人与前后状态、账本侧存在 `sourceType=SETTLEMENT / sourceId=<batchId>` 的平衡 Posting；重复收敛断言幂等（无第二条 Posting）；关闭断言 `CLOSED` 且重复关闭幂等；对 `FAILED` 收敛断言**不**记账且 `settlement.failed` 递增；整个流程断言**无任何真实出款路径**。

**Acceptance Scenarios**:

1. **Given** 批次处于 `UNKNOWN`，**When** 按 `merchantId` + `period` 查询，**Then** 返回该批次完整金额（`income/refund/adjustment/net`）与状态；不存在的组合返回 `NOT_FOUND`。
2. **Given** 批次处于 `UNKNOWN`/`EXECUTING`，**When** 以 `status=SUCCEEDED` 且携带非空 `operator`+`reason` 收敛，**Then** 批次进 `SUCCEEDED`，写 `FINANCIAL_AUDIT`（含 `fromStatus`/`toStatus`/操作人/理由/`traceId`）；`operator`/`reason` 为空则被拒（`INVALID_ARGUMENT`）。
3. **Given** 批次已 `SUCCEEDED`（且 ADR-0023 采纳记账），**When** 记账触发，**Then** `ledger-service` 生成来源 `SETTLEMENT:<batchId>` 的**平衡** Posting（DEBIT `MERCHANT_PAYABLE` / CREDIT `SETTLEMENT_PAYABLE`，金额 = `netMinor`），且重复收敛**不产生**第二条 Posting（幂等键唯一约束 + 先回查）。
4. **Given** 记账 RPC 失败或超时，**When** 收敛为 `SUCCEEDED`，**Then** 批次状态**不回滚**（禁 2PC/XA），递增 `ledger.posting_failed`（`module=settlement`）并记录待记账，由重试/对账兜底。
5. **Given** `netMinor <= 0`，**When** 收敛为 `SUCCEEDED`，**Then** **不**发起记账请求（账本要求分录金额 > 0，`LedgerEntry.java:29-32`），并写审计/指标说明跳过原因。
6. **Given** 批次处于 `SUCCEEDED`/`FAILED`，**When** 请求 `POST .../close`，**Then** 进 `CLOSED`；重复关闭**幂等吸收**；处于 `UNKNOWN`/`EXECUTING` 请求关闭则被拒（`STATE_TRANSITION_VIOLATION`）。
7. **Given** 批次被收敛为 `FAILED`，**When** 检查账本，**Then** **无**该批次的 Posting（只有成功结算才结转），并递增 `settlement.failed`。
8. **Given** 整个 Phase 7 的任何路径，**When** 代码级检视，**Then** 不存在任何向银行/渠道发起真实出款的调用（Roadmap 硬约束）。

---

### Edge Cases

- **同商户+周期并发建批**：先 `findByMerchantAndPeriod` 回查，未命中则插入；并发撞 `uk_settlement_batches_merchant_period` 捕获 `DuplicateKeyException` 后回查返回首次批次（已实现，保持不变）。
- **幂等键与商户+周期不一致**：命中幂等键但 `merchantId`/`period` 与请求不同 ⇒ **报错**（`DUPLICATE`），**MUST NOT** 静默返回他商户/他周期批次（N5，当前为静默返回）。
- **并发更新批次**：`version` 乐观锁，`updateById` 0 行命中抛 `CONFLICT`（已实现；close/记账状态回写同样受保护）。
- **调整项金额方向**：调整项自身金额 `> 0`，方向由 `direction`（`CREDIT` 补差 / `DEBIT` 扣款）表达；批次 `adjustmentMinor` 为**带符号**求和，净额语义见 **ADR-0022**（改变 `SettlementBatch.compute` 既有不变量，属 Constitution §8.8 范围，MUST 先确认）。
- **负净额**：`net = income − refund + adjustment` 允许为负（沿用现有 `compute` 不拒绝负值的语义）；负净额**不**记账（US3 场景 5），并计入 `settlement.negative_net` 指标供人工关注，语义是否拒绝属 `[待定]`。
- **调整项撤销 / 冲正**：MVP 只支持在**建批前**登记；建批后撤销需反向调整项并归属新周期，或直接拒绝（见 ADR-0022）；**MUST NOT** 修改已生成批次的金额（批次即事实快照）。
- **账单/事实侧无商户维度（N1）**：契约里没有 `merchantId`，settlement 无法在本地把事实归属到商户；本 Spec **不静默**——批次记录参与计算的事实条数与来源周期，缺口本身记入 ADR-0023 并另立契约变更议题（Constitution §8.4）。
- **reconciliation 不可用 / 超时**：出站 RPC 按配置超时（[目标] connect 1s / read 3s），对只读幂等 GET 有限重试（[目标] 3 次 / 1s-2s-4s）；失败**不落批次**，调用方可安全重跑（与 `006-reconciliation` FR-013/FR-014 同口径）。
- **账本不可用**：记账失败不回滚批次，进待记账兜底；**MUST NOT** 用「批次状态」冒充账务事实（Constitution §II.3）。
- **金额溢出/精度**：全程 `long` 最小货币单位，差异/求和保持整数语义，**MUST NOT** 出现 `float`/`double`（Constitution §II.1）。
- **跨服务 traceId 断裂**：对账读取、记账与审计 MUST 透传 `traceId`（`TraceContext` / Feign 拦截器）。

## Out of Scope（明确不做）

按 Roadmap Phase 7「不包含」，并在本 Spec 中重申：

- **真实出款 / 银行对接**：本 Feature 与 Phase 7 全程模拟执行，无真实资金划出。
- **多币种清分、税费、复杂分账**：`[Phase 延后]`；本 Feature 只做「混币种显式拒绝」，不做汇率与清分。
- **在 settlement 内实现账本**：记账统一走 `ledger-service`（已实现，`004-ledger`），settlement 只做出站调用。
- **为 `settlement-summary` / `confirmed-facts` 增加 `merchantId`（N1）**：跨服务契约变更（Constitution §8.4），本 Feature **不做**，口径记 ADR-0023。
- **调整项工作流/审批流**：MVP 只要求操作人 + 理由 + 幂等 + 审计；审批与权限体系在 Phase 9（Risk / Security）统一建设（同 ADR-0006 的取舍）。
- **熔断 / 降级（Resilience4j）**：无真实负载证据，Constitution §IV 基础设施门槛未过。
- **结算调度器（定时触发建批）**：当前由运维/测试显式调用；进程内调度器属 `[待定]`。

## Requirements

### Functional Requirements

- **FR-001**: 调整项 MUST 持久化在 settlement 自有 Schema（新表 `settlement_adjustments`），字段至少含 `merchant_id` / `period` / `direction`(CREDIT/DEBIT) / `amount_minor` / `currency_code` / `reason` / `operator` / `idempotency_key` / `status`(ACTIVE/REVOKED)（Constitution §8.3 新增关键资金表，须确认）。
- **FR-002**: 调整项登记 MUST 携带调用方幂等键（DB 唯一约束 `uk_settlement_adjustments_idem` 兜底）、非空 `reason` 与非空 `operator`；重复提交 MUST 幂等返回首次结果；同键不同参数 MUST 被拒绝，**MUST NOT** 静默覆盖。
- **FR-003**: `createBatch` MUST 汇总该 `(merchant, period)` 的全部 `ACTIVE` 调整项参与净额计算，并为每条生成一条 `ADJUSTMENT` 明细（reference = 调整项业务编号）。
- **FR-004**: 净额 MUST 遵循 ADR-0022 选定的唯一公式（`net = income − refund + adjustment`，`adjustment` 为带符号调整合计），全程 `long` 最小货币单位，**MUST NOT** 使用 `float`/`double`（Constitution §II.1）。
- **FR-005**: 金额与批次不变量 MUST 由 `SettlementBatch.compute` 集中校验：`income >= 0`、`refund >= 0`、`adjustmentMinor == sum(ADJUSTMENT 明细带符号金额)`、明细与合计一致；不满足抛 `AMOUNT_INVARIANT_VIOLATION`。
- **FR-006**: 该 `(merchant, period)` 已存在结算批次时，新登记调整项 MUST 被拒绝（`STATE_TRANSITION_VIOLATION`），保证批次净额等于创建时快照；撤销/冲正路径见 ADR-0022。
- **FR-007**: 「未确认事实不得结算」闸门 MUST 在 settlement 本地对**逐条事实**执行校验：`type ∈ {PAYMENT, REFUND}`、`currencyCode == 批次币种`、`amountMinor >= 0`、`summary.period == 请求 period`；任一不满足 MUST 拒绝建批，**MUST NOT** 静默忽略或静默混算。
- **FR-008**: 闸门拒绝 MUST 递增 `settlement.gate_rejected{reason=...}` 并打印含 `traceId`/`merchantId`/`period` 的结构化日志，且 MUST **不落**任何批次记录（不落半成品）。
- **FR-009**: reconciliation 返回 404（该周期无对账批次）MUST 归一化为 `NOT_FOUND`；其他出站失败 MUST 归一化为 `INTERNAL_ERROR`（或 `DEPENDENCY_UNAVAILABLE`），**MUST NOT** 冒泡为未处理异常。
- **FR-010**: settlement MUST NOT 存在任何回写 reconciliation / payment / refund 原始事实的代码路径（只读消费 `settlement-summary`，Constitution §III 边界 #4）。
- **FR-011**: 资金入口（建批、登记调整项、收敛）MUST 有幂等键，MUST 由数据库唯一约束兜底：`uk_settlement_batches_idempotency_key`、`uk_settlement_batches_merchant_period`、`uk_settlement_adjustments_idem`。
- **FR-012**: 幂等键命中时 MUST 校验 `merchantId`/`period` 与请求一致；不一致 MUST 报错（`DUPLICATE`），**MUST NOT** 静默返回其他商户/周期的批次（N5）。
- **FR-013**: 所有批次状态迁移 MUST 经 `SettlementBatch` 集中方法（`calculate`/`markReady`/`execute`/`succeed`/`fail`/`markUnknown`/`close`），MUST NOT 散落 `setStatus`；并发更新由 `version` 乐观锁保护（`CONFLICT`）（Constitution §V.2）。
- **FR-014**: 系统 MUST 提供 `POST /internal/settlements/batches/{id}/close`：`SUCCEEDED`/`FAILED` → `CLOSED`，重复关闭 MUST 幂等吸收；非终态关闭 MUST 被拒（`STATE_TRANSITION_VIOLATION`）（N3）。
- **FR-015**: 系统 MUST 支持按 `merchantId` + `period` 查询批次（状态查询），并保留按 `id` 查询；不存在的组合返回 `NOT_FOUND`。
- **FR-016**: 结果收敛（`resolve`）MUST 携带非空 `operator` 与 `reason`，并写入 `FINANCIAL_AUDIT`（含 `traceId`、前后状态、金额、操作人、理由）；`UNKNOWN`/`FAILED`/关闭各留审计（N6）。
- **FR-017**: 若 ADR-0023 采纳记账，批次收敛为 `SUCCEEDED` 时 MUST 经同步 RPC 向 `ledger-service` 提交一笔平衡 Posting（DEBIT `MERCHANT_PAYABLE` / CREDIT `SETTLEMENT_PAYABLE`，金额 = `netMinor`），幂等键固定为 `SETTLEMENT:<batchId>`；记账失败 MUST NOT 回滚批次状态，MUST 递增 `ledger.posting_failed`（`module=settlement`）并进入待记账兜底（Saga + 幂等，禁 2PC/XA）。
- **FR-018**: `netMinor <= 0` MUST NOT 发起记账请求（账本要求分录金额 > 0），并 MUST 记录审计/指标说明跳过原因。
- **FR-019**: 出站 RPC（merchant / reconciliation / ledger）MUST 显式配置超时（`[目标]` connect 1s / read 3s）与对**幂等只读 GET** 的有限重试（`[目标]` 3 次 / 1s-2s-4s）；**MUST NOT** 对写操作重试；**MUST NOT** 引入 MQ / 跨服务异步事件 / 2PC/XA / Resilience4j（Constitution §IV、§V.4/§V.6）。
- **FR-020**: 本 Feature MUST NOT 实现真实出款、银行对接、多币种清分、税费与复杂分账；代码级 MUST NOT 存在真实资金划出路径（Roadmap Phase 7 硬约束）。
- **FR-021**: Database-per-service：settlement-service 只读写自有 `settlement` Schema，MUST NOT 直接 SQL 他服务表（Constitution §IV.4）。
- **FR-022**: 多币种清分明确延后：币种字段保留，但币种不一致 MUST 显式拒绝（`AMOUNT_INVARIANT_VIOLATION`），**MUST NOT** 静默相加（G3）。
- **FR-023**: 金额路径与状态机路径 MUST 有单元测试与集成测试（调整项净额、幂等吸收、闸门拒绝、乐观锁冲突、记账幂等、记账失败不回滚）；**MUST NOT** 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。
- **FR-024**: 实现完成后 MUST 同步修正文档状态漂移：`technical-solution.md:106`（settlement「骨架」）、`:101`（ledger-service「延后 Phase 8」）、`settlement-service.md:91`（`adjustment_minor（MVP=0）`）与 `roadmap.md` Current Status（G5）。

### Key Entities

- **SettlementBatch（结算批次聚合根，已实现 + 扩展）**：商户某周期的结算事实（收入/退款/**调整**/净额）与生命周期。本 Feature 扩展：调整额参与计算并带符号（ADR-0022）、批次记录参与计算的事实条数与来源周期（闸门证据）、`close()` 经端点可达。位置 `settlement-service/.../domain/SettlementBatch.java`。
- **SettlementItem（明细值对象，已实现 + 语义扩展）**：`type` 从「仅 PAYMENT/REFUND 实际产生」扩展为「`ADJUSTMENT` 明细真实存在且带符号」（`.../domain/SettlementItem.java`）。
- **SettlementAdjustment（调整项实体，新增）**：替代当前零引用的 `Adjustment` 记录（`.../domain/Adjustment.java:6`），承载商户/周期/方向/金额/币种/理由/操作人/幂等键/状态；随 `settlement_adjustments` 表持久化。
- **ConfirmedFactGate（闸门组件，新增）**：对 `ReconciliationSummary` 逐条事实执行 FR-007 校验的纯函数/领域服务，产出「通过 / 拒绝原因」，无外部依赖、可单测。
- **LedgerPostingGateway（出站端口，新增；依赖 ADR-0023）**：settlement → ledger-service 的同步 RPC 边界（对齐 `payment-service/.../application/LedgerPostingGateway.java`），封装超时/重试/幂等键与失败兜底。
- **SettlementBatchResponse（响应 DTO，已实现 + 扩展）**：新增 `factCount` / 调整项相关可追溯字段，便于运维核对「明细 = 金额」（`.../api/SettlementBatchResponse.java`）。

## Observability（本 Feature 指标与审计）

> 既有 `settlement.batch_initiated` / `settlement.unknown` / `settlement.failed` 与 `FINANCIAL_AUDIT` 保持不变（Constitution §VII）。

**新增指标（Micrometer `BusinessMetrics`，维度含 `module=settlement`）**

| 指标键 | 类型 | 维度 | 触发点 |
|---|---|---|---|
| `settlement.adjustment_registered` | counter | `direction` | 调整项登记成功 |
| `settlement.adjustment_rejected` | counter | `reason`（`invalid_argument`/`batch_exists`/`currency_mismatch`/`duplicate`） | 登记被拒 |
| `settlement.gate_rejected` | counter | `reason`（`unknown_fact_type`/`currency_mismatch`/`negative_amount`/`period_mismatch`/`no_reconciliation`） | 闸门拒绝建批（FR-008） |
| `settlement.negative_net` | counter | — | 净额 ≤ 0（不记账、需人工关注） |
| `settlement.closed` | counter | — | 批次关闭成功 |
| `ledger.posting_succeeded` / `ledger.posting_failed` | counter | — | 记账结果（对齐 payment 侧命名，FR-017） |

**资金审计（`StructuredAuditLogger`，单行 JSON）**

- `action`：`settlement.adjustment_registered` / `settlement.batch_initiated` / `settlement.unknown` / `settlement.failed` / `settlement.closed` / `settlement.ledger_posted`。
- 字段键：`traceId`、`idempotencyKey`、`amountMinor`、`currencyCode`、`fromStatus`、`toStatus`、`entityType`、`entityId`；调整项与收敛动作额外带 `operator`、`reason`（FR-002 / FR-016）。
- 敏感信息（密钥、账户）MUST 脱敏，审计不含原始事实明细全文。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 调整项 100% 参与净额计算：任意调整项组合下 `netMinor` 与手工计算一致；`ADJUSTMENT` 明细带符号求和 100% 等于 `adjustmentMinor`。
- **SC-002**: 调整项登记幂等率 100%（同键不重复计入）；空理由/空操作人/批次已存在/币种不一致 100% 被拒并留痕，0 次静默成功。
- **SC-003**: 闸门 100% 生效：未知 `type`、币种不一致、负金额、周期不一致的事实 100% 导致建批被拒；「进明细却不进金额」的事实数为 **0**。
- **SC-004**: 周期无对账批次 100% 返回 `NOT_FOUND`，0 次未归一化异常（500）。
- **SC-005**: 幂等 100%：同键 / 同商户+周期重复请求返回首次批次；跨商户/周期复用幂等键 100% 被拒，0 次静默错配。
- **SC-006**: 状态机 100% 经唯一入口；非法迁移 100% 被拒；`close` 100% 幂等；并发更新 100% 由乐观锁拦截（`CONFLICT`）。
- **SC-007**: 记账（若 ADR-0023 采纳）：`SUCCEEDED` 批次 100% 生成平衡 Posting（幂等键 `SETTLEMENT:<batchId>`），重复收敛 100% 幂等吸收（无第二条）；记账失败 0 次回滚批次状态。
- **SC-008**: 可观测 100%：建批/闸门拒绝/UNKNOWN/失败/关闭/记账成功/记账失败均有指标；关键动作 100% 写入含 `traceId`/操作人/理由的 `FINANCIAL_AUDIT`。
- **SC-009**: 出站 RPC 100% 按配置超时；瞬时故障 100% 触发有限重试（≤ 3 次、1s/2s/4s），且仅作用于幂等只读 GET。
- **SC-010**: `./mvnw verify` 全量通过；金额路径 0 处 `float`/`double`；代码级 0 条真实出款路径；Database-per-service 0 处跨服务 SQL。

## Assumptions

- `settlement-service`（8089 / Schema `settlement`）核心链路已实现，本 Feature 只补缺口与扩展，**不重写**既有状态机、持久化与幂等机制。
- 不引入 MQ / 分布式事务 / 跨服务异步事件 / 熔断中间件；跨服务为同步 RPC + 幂等 + 有限重试（Constitution §IV、ADR-0001）。
- 当前单节点部署；建批/登记调整项由运维或测试显式触发，**假定同一 (商户, 周期) 同一时刻只有一个执行者**（并发由 DB 唯一约束兜底）。
- MVP 单币种（`CNY`）；多币种清分、税费、复杂分账不在本 Feature。
- 调整项方向语义、登记端点与权限边界见 **ADR-0022**；闸门纵深防御与 settlement→ledger 记账归属/时机见 **ADR-0023**；**实现前 MUST 由负责人确认**（Constitution §8.2/§8.3/§8.4/§8.8）。
- 记账部分依赖 `ledger-service`（8090）已实现且 `004-ledger` ADR-0011「结算跟随」的判断；若负责人选择遵循 Roadmap Phase 7「不实现 Ledger」，US3 的记账部分降级为 `[待定]`（见 ADR-0023 备选方案 B）。
- 超时（connect 1s / read 3s）与重试（3 次 / 1s-2s-4s）为建议默认值，可通过配置覆盖。

## Dependencies（依赖与前置）

| 依赖 | 状态 | 说明 |
|---|---|---|
| Phase 2/3/5（Payment / Reliability / Refund） | 已完成 | 支付与退款的已确认事实是结算的输入 |
| `reconciliation-service` `GET /internal/reconciliation/settlement-summary` | 已实现（无商户维度 N1；周期无批次抛 404） | 结算唯一事实来源；本 Feature 只加超时/重试/错误归一化，**不改契约** |
| `merchant-service` `GET /merchants/{id}` | 已实现 | 资格校验；本 Feature 加超时/重试 |
| `ledger-service` `POST /internal/ledger/postings` | **已实现**（8090） | 记账目标；本 Feature 是否接入由 ADR-0023 决定 |
| `deployment/schema/08-settlement-schema.sql` | 已实现（需扩展） | 本 Feature 新增 `settlement_adjustments` 表 + `settlement_batches` 少量列（Constitution §8.3） |
| `common-core` `StructuredAuditLogger` / `BusinessMetrics` / `Money` | 已实现 | 结算侧已用审计与指标；`Money` VO 是否引入结算侧见 ADR-0022 相关取舍 |
| `common-dto` `PostingRequest` / `PostingResponse` | 已实现 | 记账契约；settlement-service 的 pom 已依赖 `common-dto` |

## Clarifications

### Session 2026-08-29

- **编号约定**：spec 目录采用顺序编号 `007-settlement`，与 Roadmap 阶段标签「**005 Settlement** / Phase 7」**解耦**（Roadmap 标签为阶段描述，非 spec ID；同 `003-payment-reliability`「Roadmap 002」、`004-ledger`「Roadmap 006」、`005-refund`「Roadmap 003」、`006-reconciliation`「Roadmap 004」的既定约定）。
- **Spec 性质**：本 Spec 为**缺口补齐型**（gap-closing / completion），非绿地构建。五项缺口 G1~G5 见文首表格，均已核实到 `file:line`；另发现 N1~N6 一并记录。
- **分歧点 → ADR**（`docs/adr/0008-settlement-decisions.md`，状态 **Proposed**，待负责人决策）：
  - 调整项（Adjustment / ADJUSTMENT 明细）如何变真实 + 谁可以创建（方向语义、持久化形态、登记门禁、权限与审计）→ **ADR-0022**。
  - 「仅已确认事实可结算」闸门：继续委托 reconciliation vs 本地强制校验（含跨服务契约缺口 N1 的归属）→ **ADR-0023**。
  - （附带，同一 ADR 内）settlement → ledger-service 记账的归属与时机 vs `004-ledger` US3 → **ADR-0023**。
- **新发现的矛盾（未写入 ADR 主体，供负责人知悉）**：
  1. **事实无商户维度（N1）**：`PlatformFact.java:13`、`ReconciliationSettlementFact.java:7-8`、`SettlementFact.java:6` 均无 `merchantId`，`createBatch` 会把周期内**所有商户**的事实算入任一商户批次（`SettlementApplicationService.java:70-77/82-84`）。这是比 G2 更根本的正确性缺口，但修它需要改跨服务契约（Constitution §8.4），本 Spec **不静默改**，仅以 FR-007/FR-022 保证「不静默」，并由 ADR-0023 记录归属。
  2. **`adjustment` 恒 0 与 Roadmap 的直接冲突（G1）**：Roadmap Phase 7「包含：收入、退款和**调整项**的最小净额计算」，而 `SettlementApplicationService.java:80` 硬编码 `0`、`Adjustment.java:6` 全项目零引用。这是 Roadmap 与代码之间**已存在的、未记录的矛盾**，本 Spec 首次显式记录。
  3. **「不实现 Ledger」与 Constitution §II.3 的新矛盾（G4）**：Roadmap Phase 7「不包含：…不实现 Ledger」，但 ledger-service 已实现且 Constitution §II.3 要求一切资金变动 MUST 经 ledger-service。二者需在 ADR-0023 中由负责人裁定（本 Spec 默认方案 A：本 Feature 接入记账；备选方案 B：遵循 Roadmap 延后）。
  4. **幂等键跨商户/周期静默错配（N5）**：`SettlementApplicationService.java:49-52` 命中即返回，不校验 `merchantId`/`period`，复用错键会返回他商户批次——与 Constitution §V.1「相同幂等键 MUST NOT 产生重复资金动作」的风险面相关，本 Spec 以 FR-012 收口（属**行为变更**，需确认）。
  5. **文档与索引漂移（G5 细节）**：`technical-solution.md:106` 标 settlement「骨架」、`:101` 仍标 ledger-service「延后 Phase 8」（实际已运行）、`roadmap.md:11` 称已落地、`settlement-service.md:91` 写 `adjustment_minor（MVP=0）`；此外 `docs/adr/README.md:11-13` 的索引**止于 0005**，未收录已存在的 `0006-refund-decisions.md` / `0007-reconciliation-decisions.md`，且 0004 仍标注「全 Proposed」（ledger 已实现）。本 Spec 不修改 `README.md`（超出授权范围），仅在此报告，由负责人在 FR-024 文档收口时一并处置。
