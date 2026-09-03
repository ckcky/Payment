# ADR-0022 ~ ADR-0023：结算（Feature 007）架构决策集合

> 本文件合并 Feature `007-settlement` 的架构决策为单一决策记录，便于集中审阅（同 `0003` / `0004` / `0006` / `0007` 的合并风格）。
> 编号为内部决策标签（ADR-0022 ~ ADR-0023），状态独立标注。
> 涉及 Constitution §8「人类决策边界」的决策，均**待负责人确认**（2026-08-29）。

---

<a id="adr-0022"></a>
## ADR-0022: 调整项模型（方向语义 / 持久化形态 / 登记门禁 / 净额公式 / 死代码处置）

- **状态**：**Accepted**
- **日期**：2026-08-29（提出）｜2026-09-03（收口为 Accepted）
- **决策者**：项目 Owner
- **关联 Feature**：`007-settlement`（spec US1 / FR-001~FR-006 / 缺口 G1、G3）
- **关联 Constitution 条款**：§8.3（新增关键资金表）、§8.4（行为变更、新增端点）、§8.8（状态机与不变量变更）、§II.1（金额铁律）

> **🧭 收口注记（2026-09-03，Phase 5 文档治理）**
> 本 ADR 在本文件创建后已**完整落地**，但状态长期停留在 `Proposed`，与代码事实不符，
> 违反 Constitution §提交与合并节奏 ④③「ADR 状态不得长期停留在 Proposed」。
> 现按代码事实收口为 **Accepted**。下方「落地验证」逐条对照决策项与实现；
> **本注记只改状态与补充证据，未改动任何决策正文。**

#### 落地验证（2026-09-03 核对）

| 决策项 | 实现位置 | 结论 |
|---|---|---|
| 独立建表 `settlement_adjustments` | `deployment/schema/08-settlement-schema.sql`、`settlement-service/src/test/resources/schema.sql` | ✅ 已建 |
| `AdjustmentDirection` 方向枚举（金额恒 > 0） | `settlement-service/.../domain/AdjustmentDirection.java` | ✅ 已建 |
| `SettlementAdjustment` 聚合 + 仓储 | `domain/SettlementAdjustment.java`、`domain/SettlementAdjustmentRepository.java`、`infra/persistence/SettlementAdjustment{Entity,Mapper}.java` | ✅ 已建 |
| 登记端点 | `api/SettlementController.java:61` `@PostMapping("/adjustments")` | ✅ 已建 |
| 批次新增 `adjustment_minor` / `fact_count` / `source_period` | `08-settlement-schema.sql`（`adjustment_minor`、`fact_count`、`source_period`） | ✅ 已建 |
| 死代码 `domain/Adjustment.java` 保留并标注废弃 | 按决策 7 保留 | ✅ 符合 |

### Context（背景）

Roadmap Phase 7「包含的 Feature」写的是「收入、退款和**调整项**的最小净额计算」，但代码里「调整项」这一项**完全不存在**：

- `SettlementApplicationService.java:80` 硬编码 `batch.calculate(income, refund, 0, "CNY")`，调整额恒为 0。
- `domain/Adjustment.java:6` 是一条**全项目零引用**的记录（死代码），从未被任何聚合引用。
- `SettlementItem` 的 `ADJUSTMENT` 类型（`SettlementItem.java:6-8`）**从未被构造**——明细表里一条 `ADJUSTMENT` 记录都没有。

后果：净额实际只有「收入 − 退款」两项，任何补差（平台赔付）、扣款（客诉扣罚）、手续费返还都无处表达，结算金额在现实业务中不可用。这是 Roadmap 与代码之间**已存在但从未被记录**的矛盾，本 Feature 首次显式记录。

调整项不是「给数字加一笔」这么简单，它同时牵动四件事，必须先定口径：

1. **方向如何表达**：用带符号金额（正负号即方向）还是「正金额 + 方向枚举」？
2. **持久化形态**：独立建表、内嵌批次 JSON、还是复用明细？
3. **登记门禁**：建批后还能不能登记？登记错了能不能改？
4. **净额公式**：`net = income − refund + adjustment` 的 `adjustment` 是带符号还是绝对值？

此外 `Adjustment.java` 这条死代码如何处置，属 Constitution §VIII.2「一次改动只做一件事」需要显式授权的事项。

### Decision（决策）

采用「**独立建表 + 正金额配方向枚举 + 建批前登记 + 带符号净额**」：

1. **方向语义**：调整项自身金额 `amountMinor` **恒 > 0**；方向由 `AdjustmentDirection` 枚举表达——`CREDIT`（补差，增加净额）/ `DEBIT`（扣款，减少净额）。**禁止**用负数金额表达方向（负数会与「金额非负」不变量互相打假，也让审计口径出现两种真值）。
2. **持久化**：**新增独立表** `settlement_adjustments`（`uk_settlement_adjustments_idem` 幂等唯一 + `idx_settlement_adjustments_scope (merchant_id, period, status)`）。理由：调整项**先于批次登记**，内嵌批次则建批前无处存放；且它需要独立的幂等键、独立的审计记录与独立的撤销状态。
3. **登记门禁**：
   - 该 `(merchant, period)` **已存在结算批次** ⇒ 新登记被拒（`STATE_TRANSITION_VIOLATION`），并递增 `settlement.adjustment_rejected{reason=batch_exists}`。批次是创建时的事实快照，**MUST NOT** 被追溯篡改。
   - 登记参数：`reason` 与 `operator` **MUST 非空白**（与 `006-reconciliation` FR-007「人工处理依据 MUST 非空」同口径），否则 `INVALID_ARGUMENT`。
   - 幂等：同键同参返回首次；**同键不同参报 `DUPLICATE`**，**MUST NOT** 静默覆盖（Constitution §V.1）。
   - 币种：与批次币种不一致（MVP 仅 `CNY`）⇒ `AMOUNT_INVARIANT_VIOLATION`，**MUST NOT** 静默混算（G3）。
4. **净额公式（唯一）**：`net = income − refund + adjustment`，其中 `adjustment` 为**带符号**调整合计（`CREDIT` 取正、`DEBIT` 取负），可为负。不变量集中在 `SettlementBatch.compute`：`income >= 0`、`refund >= 0`、`adjustment == sum(ADJUSTMENT 明细带符号金额)`，违反一律 `AMOUNT_INVARIANT_VIOLATION`。
5. **明细**：每条 `ACTIVE` 调整项生成一条 `ADJUSTMENT` 明细（`reference` = 调整项业务编号，`amountMinor` 带符号），保证「明细 = 金额」可对账（INV-4/INV-5）。
6. **撤销**：`SettlementAdjustment.revoke()` 置 `REVOKED`，不参与后续计算；**建批后的撤销/冲正不在本 Feature**（`[待定]`），反向调整项归属新周期。
7. **死代码处置**：保留 `domain/Adjustment.java`，标注 `@Deprecated` 并加注释指向 `SettlementAdjustment`；**本 Feature 不删除**（删除属无关改动，若负责人授权可在文档收口阶段一并清理）。
8. **负净额**：`compute` **不拒绝**负值（沿用既有语义），仅递增 `settlement.negative_net` 供人工关注，且**不记账**（ADR-0023）。是否改为拒绝建批属 `[待定]`。

### 备选方案

- **A. 带符号金额（负数即扣款），不要方向枚举**：字段最少，但 `amountMinor > 0` 的不变量失效，且「−0」「双重否定」等边界会让审计口径出现歧义 —— **否决**。
- **B. 调整项内嵌 `settlement_batches.adjustments_json`**：省一张表，但调整项必须**先于批次**登记（建批时才知道净额），内嵌则建批前无处存放；且独立幂等键与撤销状态难以表达 —— **否决**。
- **C. 允许建批后登记并自动重算批次**：运维最灵活，但等于让已生成批次的净额可被追溯篡改，破坏「批次即事实快照」与终态语义（§8.8）—— **否决**。
- **D. 独立建表 + 方向枚举 + 建批前登记 + 带符号净额（采纳）**：口径唯一、可审计、不破坏快照语义。
- **E. 直接删除 `Adjustment.java`**：清理最彻底，但属与本次缺口补齐无关的改动（Constitution §VIII.2）—— **否决，改标注废弃**。

### Consequences（后果）

**正面**：Roadmap Phase 7「调整项」一条首次成立；净额口径唯一且集中在 `compute`，可单测；每条调整都有操作人、理由与审计；明细与金额 100% 可对账。

**代价 / 风险**：

- 新增关键资金表 `settlement_adjustments` + `settlement_batches` 两列（`fact_count` / `source_period`），属 Constitution §8.3，须确认。
- 登记端点 `POST /internal/settlements/adjustments` 属 §8.4（新增，向后兼容）。
- `compute` 的不变量与净额语义变更属 §8.8。
- 「建批后不能登记」会限制运营灵活性（发现漏登只能走下周期反向调整）——这是**有意取舍**（批次即快照）。
- 存量 `settlement_batches` 的 `fact_count` 取默认 0、`source_period` 为 NULL，**不回填**（回填等于伪造事实，同 `006-reconciliation` 取舍）。

### 关联

- Constitution §II.1、§V.1、§V.2、§8.3、§8.4、§8.8、§VIII.2
- `007-settlement` spec：US1、FR-001~FR-006；data-model.md §2/§3/§4、INV-2~INV-7
- 代码：`settlement-service/.../application/SettlementApplicationService.java:80`、`domain/Adjustment.java:6`、`domain/SettlementItem.java:6-8`

---

<a id="adr-0023"></a>
## ADR-0023: 已确认事实闸门的纵深防御与 settlement → ledger 记账归属

- **状态**：**Accepted**
- **日期**：2026-08-29（提出）｜2026-09-03（收口为 Accepted）
- **决策者**：项目 Owner
- **关联 Feature**：`007-settlement`（spec US2 / US3 / FR-007~FR-009、FR-012、FR-017~FR-018 / 缺口 G2、G4；新发现 N1、N2、N5）
- **关联 Constitution 条款**：§II.3（一切资金变动 MUST 经 ledger-service）、§8.4（跨服务契约变更）、§III 边界 #4（Settlement 零回写）、§V.7（未确认结果不落账）

> **🧭 收口注记（2026-09-03，Phase 5 文档治理）**
> 本 ADR 已完整落地，状态长期停留在 `Proposed` 与代码事实不符，现按事实收口为 **Accepted**。
> **本注记只改状态与补充证据，未改动任何决策正文。**
>
> #### 落地验证（2026-09-03 核对）
>
> | 决策项 | 实现位置 | 结论 |
> |---|---|---|
> | `ConfirmedFactGate` 本地逐条强制（纯函数、可单测） | `settlement-service/.../application/ConfirmedFactGate.java` | ✅ 已建 |
> | settlement → ledger 记账出站端口 | `application/LedgerPostingGateway.java` | ✅ 已建 |
> | Feign 适配 + 客户端 + 配置 | `infra/client/FeignLedgerPostingGateway.java`、`infra/client/LedgerFeignClient.java`、`infra/client/LedgerFeignConfig.java` | ✅ 已建（复用 payment-service 既有模式） |
> | 批次记录 `fact_count` / `source_period` | `08-settlement-schema.sql:19-20`、`domain/SettlementBatch.java:33,35,118,127` | ✅ 已建 |
> | 不引入 Resilience4j | 决策 G 已否决；settlement 侧无熔断依赖 | ✅ 符合 |
>
> ⚠️ **遗留风险未消解**：**N1（事实无 `merchantId`）按决策明确不在本 Feature 修复**，仅以 `fact_count` /
> `source_period` 做到「不静默」。跨商户串账的可能性依然存在，是否单独立项仍待负责人决定
> （见 Decision 1 末段与 Consequences）。

### Context（背景）

本 ADR 裁定三件彼此耦合、且都超出「补一个 if」范围的事。

**（1）闸门：委托 vs 本地强制。** 当前「未确认事实不得结算」完全委托给 reconciliation 的一个计数 `unresolvedDifferenceCount`（`SettlementApplicationService.java:62-68`）：只要计数为 0 就建批，settlement **自身不逐条校验**它即将结算的每一条事实。于是出现两类静默错误：

- 事实 `type` 出现约定外取值（如 `FEE`）时，既不计入收入也不计入退款，却被 `addItem` 写进明细——「**在明细里但不在金额里**」（G2）。
- 事实币种与批次币种不一致时被静默相加——混币种静默相加＝错账（G3）。

更根本的是 **N1**：`PlatformFact` / `ReconciliationSettlementFact` / `SettlementFact` **都没有 `merchantId`**。`createBatch` 把该周期的**全部商户**事实算入任一商户的批次，而 `(merchant_id, period)` 唯一约束允许商户 B 生成一份含商户 A 事实的批次。**这是比 G2 更根本的正确性缺口**，但修它需要改跨服务契约（Constitution §8.4）。

**（2）记账：Roadmap 与 Constitution 的直接冲突。** Roadmap Phase 7 原文「不包含：…不实现 Ledger」，但 Constitution §II.3 要求「一切资金变动 MUST 经 ledger-service」，而 `ledger-service`（8090）**已实现并运行**，`Account.SETTLEMENT_PAYABLE(4)`、`LedgerSourceType.SETTLEMENT`、`LedgerEntry.Type.SETTLEMENT` 均已就位，payment 侧网关也已落地。004-ledger 的 US3「结算侧」仍悬空。

**（3）N5 幂等键错配的静默。** `SettlementApplicationService.java:49-52` 按幂等键命中即返回，**不校验** `merchantId`/`period` 与本次请求是否一致——复用错键会静默返回他商户/他周期的批次，是资金错配面。

### Decision（决策）

**1. 闸门：本地逐条强制 + 不落半成品，N1 不静默改契约**

- 新增 `ConfirmedFactGate`（纯函数、无外部依赖、可单测），逐条校验：`type ∈ {PAYMENT, REFUND}`、`currencyCode == 批次币种`、`amountMinor >= 0`、`summary.period == 请求 period`。任一不满足 ⇒ 拒绝建批，**不落任何批次记录**。
- 拒绝时递增 `settlement.gate_rejected{reason=unknown_fact_type|currency_mismatch|negative_amount|period_mismatch|no_reconciliation}` 并打印含 `traceId`/`merchantId`/`period` 的 WARN。
- reconciliation 返回 404（该周期无对账批次）⇒ 归一化为 `NOT_FOUND`（N2，「无对账 ⇒ 不可结算」），其他出站失败 ⇒ `INTERNAL_ERROR`，**MUST NOT** 冒泡为未处理异常。
- 建批成功后批次记录 `fact_count`（参与计算的事实条数）与 `source_period`（来源周期），让「明细 = 金额」可事后核对。
- **N1（事实无商户维度）本 Feature 不改契约**：不在 `settlement-summary` 上加 `merchantId`（§8.4）。改为把缺口如实记入本 ADR，并以 `fact_count` / `source_period` 保证「不静默」。若负责人授权，需另立契约变更 Feature（reconciliation + payment + refund 三侧同步）。

**2. 记账：本 Feature 接入，`SUCCEEDED` 且净额 > 0 时触发**

- 新增 `application/LedgerPostingGateway` 出站端口 + `infra/client/FeignLedgerPostingGateway` + `infra/client/LedgerFeignClient`，**复用 payment-service 既有模式**（`services.ledger.url` 默认 `http://localhost:8090`）。
- 科目：`MERCHANT_PAYABLE`（DEBIT）/ `SETTLEMENT_PAYABLE`（CREDIT），金额 = `netMinor`，幂等键 `SETTLEMENT:<batchId>`，`sourceType=SETTLEMENT`、`sourceId=<batchId>`。
- 触发条件：**仅**收敛为 `SUCCEEDED` 时；`UNKNOWN`/`EXECUTING`/`FAILED`/`CLOSED` **不记账**（§V.7）。`netMinor <= 0` **不发起**（账本要求分录金额 > 0，`LedgerEntry.java:29-32`），并记录跳过原因。
- 失败兜底：RPC 失败/超时 ⇒ **不回滚**批次状态（禁 2PC/XA），递增 `ledger.posting_failed`（`module=settlement`）写 `FINANCIAL_AUDIT`，交重试/对账兜底。
- 归属：`ledger-service` 的领域模型与端点契约归 `004-ledger`（已实现）；**结算侧接入归 `007-settlement`**，`004-ledger` 的 US3 结算部分标记为「由 007 承接」（同 `006-refund` 处理退款记账的既有先例）。

**3. N5：幂等键命中后校验商户与周期一致性**

命中幂等键时 MUST 校验 `merchantId`/`period` 与请求一致，不一致报 `DUPLICATE`，**MUST NOT** 静默返回他商户/他周期批次（FR-012）。这是行为变更（由静默成功改为报错），属 §8.4，须确认。

**4. 出站弹性**：为 merchant / reconciliation / ledger 三个客户端显式配置 `Request.Options`（connect 1s / read 3s）；仅对**幂等只读 GET**（merchant 查询、reconciliation summary）配置 `feign.Retryer`（3 次 / 1s-2s-4s），**MUST NOT** 对写操作（记账 POST）重试；自定义 `ErrorDecoder` 归一化异常。修复当前对 Constitution §V.6 的违反（N4）。**不引入** Resilience4j（无故障证据，Constitution §IV 门槛未过）。

### 备选方案

- **A. 闸门继续委托 reconciliation（现状）**：零改动，但「未确认事实不得结算」只是架构假设，未知 `type` 与混币种会静默产出错账 —— **否决**。
- **B. 本地全量复核（对每条事实回查 payment/refund 确认状态）**：最严格，但等于在 settlement 里重复实现对账，违反 Constitution §III「Settlement ≠ Reconciliation」，且原始事实无商户/周期维度可回查 —— **否决**（复杂度与边界双重失当）。
- **C. 为 `settlement-summary` 增加 `merchantId` 以根治 N1**：语义最正确，但属跨服务契约变更（§8.4），需 payment/refund 事实链路同步改造 + 索引，Roadmap Phase 7 未授权 —— **否决（另立契约变更议题）**。
- **D. 记账遵循 Roadmap Phase 7 延后**：与 Roadmap 原文一致，但会留下「已确认结算无账务分录」的硬缺口，违反 Constitution §II.3，且 ledger-service 已就绪、payment 侧已接入，延后只会造成新的不对称 —— **否决**（Roadmap 该句需按本 ADR 修订）。
- **E. settlement 自建 `ledger_entries` 镜像表**：省一次 RPC，但违反 Database-per-service 与「Ledger 只被依赖」—— **否决**。
- **F. 幂等键错配保持静默返回（现状）**：零改动，但会静默返回他商户批次（资金错配）—— **否决**。
- **G. 引入 Resilience4j 做熔断**：能力最全，但无负载/故障证据，属 Constitution §IV 禁止的「为复杂度引入中间件」—— **否决（留待有证据时另立 ADR）**。

### Consequences（后果）

**正面**：「未确认事实不得结算」从架构假设变为运行时强制且留痕；混币种与未知类型 0 次静默；结算侧满足 Constitution §II.3，与支付侧、退款侧记账形成完整闭环，全局借贷可平衡；幂等键错配不再静默；出站调用显式超时，修复 §V.6 既存违反。

**代价 / 风险**：

- 新增端点（`POST /adjustments`、`POST /batches/{id}/close`、`GET /batches?merchantId=&period=`）与响应字段，属 §8.4（均为新增，向后兼容）；但 **FR-012 幂等键错配由「静默返回」改为「报错」属行为变更**。
- 记账失败会留下「批次已成功但账本无分录」的中间态，依赖对账补齐——与支付侧既有取舍一致（ADR-0009），可接受但 MUST 有指标与告警。
- 记账走同步链路，账本不可用会增加收敛延迟；当前无熔断/降级（`[Phase 按需延后]`）。
- **N1 未根治**：跨商户串账的可能性依然存在（事实无商户维度），本 Feature 只做到「不静默」。这是本 ADR 最重要的遗留风险，须由负责人决定是否单独立项。
- 重试仅作用于只读 GET；记账 POST 失败不重试，靠幂等键 + 待记账兜底，运维需知晓。

### 关联

- Constitution §II.1、§II.3、§III 边界 #4、§IV、§V.4、§V.6、§V.7、§VII、§8.3、§8.4、§8.8
- `007-settlement` spec：US2、US3、FR-007~FR-009、FR-012、FR-017~FR-019、FR-022；data-model.md INV-6、INV-9、INV-13、INV-14、INV-17
- `docs/adr/0004-ledger-design-decisions.md`（ADR-0008~0011）、`docs/adr/0006-refund-decisions.md`（ADR-0018 退款记账先例）
- 代码：`settlement-service/.../application/SettlementApplicationService.java:49-52/62-68/70-84`、`infra/client/FeignReconciliationClient.java:23-29`、`payment-service/.../infra/client/FeignLedgerPostingGateway.java`
