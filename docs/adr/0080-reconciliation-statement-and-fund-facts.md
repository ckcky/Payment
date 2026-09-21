<a id="adr-0080"></a>

# ADR-0080：对账真实化——渠道账单导入对象 + 事实维度升级 + 渠道资金事实入账（Feature 032）

> 承载 [spec 032-reconciliation-real-statement](../specs/stage-05-channel-and-finance-deepening/032-reconciliation-real-statement/spec.md) 的六条决策。
> 状态：**🟡 Proposed（2026-09-21 提出，待负责人裁决）**——涉及 Constitution §Governance
> 「人类决策边界」（跨服务 DTO 变更 = API Breaking、资金口径变更、新增关键表、差异处置语义），
> **未 Accepted 前不得实现**。
> 编号说明：Feature 编号 **032**（stage-design §9.2 分配）；依赖 [ADR-0077~0079](0077-ledger-accounting-foundation-decisions.md)（031 账务地基）。

---

## Context

代码走查（design-review v1.1 的 C-13 / C-20 / C-22 / R1 + spec 032 §1 的 18 条【现状】证据）确认：

1. **对账没有外部锚点**。渠道账单来源是 classpath CSV fixture，且**周期未命中会静默回退 `sample.csv`**
   （`CsvChannelStatementLoader.java:95-99`）——拿别的周期的账单当真账单对；畸形行 WARN 后跳过（`:110-132`）。
   标准化行只有 4 个字段（`reference/amountMinor/currencyCode/status`），**装不下渠道、商户、费用、发生时间**。
2. **对账与结算事实缺两个维度**：`merchantId` 与期间。上游 `PaymentFactResponse` 已给出 `merchantId`，
   是 reconciliation 在边界处丢掉的（`PaymentFactDto.java:7`）；`FeignAuditFactsGateway.java:42,48` **忽略 `period` 参数**全量拉取。
   后果是 **C-20：跨商户串账 + 跨期重复结算**（资金归属错误与重复打款），severity 已从 M4 升级为 H11。
3. **结算事实来源错**：批次只吃「匹配成功的 `matches`」（`ReconciliationApplicationService.java:186-189`）⇒
   未匹配上的事实**永不结算**且无告警（C-13 静默漏结算）。
4. **渠道侧资金事实无处入账**：账单里的渠道实收净额与渠道手续费是**外部世界对「钱实际到哪了」的答复**，
   今天完全不进账——031 §16 明确把 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 的产生方**挂起交给 032**。
5. **差异不可索引**：匹配与差异以 JSON 内嵌存进行（`07-reconciliation-schema.sql:8-27`），不可分页、不可按状态查询、不可重放。

---

## Decision

### 决策 1：渠道账单成为**导入对象**，获取端口留在 reconciliation 侧，不引入渠道 SDK

`statement_imports`（一次导入 = 幂等单位）+ `statement_lines`（逐行落库、可索引、可查询）成为一等公民；
幂等三层：`uk(channel_code, period, content_fingerprint)` / `uk(import_id, line_no)` / `uk(period, kind, sourceType, sourceId)`。
账单获取经 `StatementParser` 端口，**MUST NOT** 在 reconciliation 引入任何渠道 SDK
（会违反 `Payment ≠ Channel` 同级的边界纪律，且与 ADR-0076 R6「SDK 收口在渠道端口之后」重复建设）。
静默回退 `sample.csv` **必须删除**，来源不可用即显式失败（ADR-0049「配错不许静默走默认」）。

> 被否决的方案：A = 经 payment 渠道端口远程拉账单（跨服务重、把渠道协议引入对账域，延后到 032-D 再评估）；
> C = 引入对象存储/SFTP 中间层（当前规模不需要）。

### 决策 2：匹配键升级为 `(merchantId, referenceType, reference)`，三级降级

一级精确（商户 + 类型 + 渠道流水号）→ 二级跨商户兜底（类型 + 渠道流水号，**仅在账单无商户维度时**）→
三级弱匹配（商户 + 金额 + 发生时间 ±Δ，**只出候选、不改判、不自动收口**）。
匹配对象按 `referenceType` 分离：Payment / Refund / Settlement 三类事实各自成套，不再共用一个 `reference` 池。

### 决策 3：差异处置**复用**四核对 5 态生命周期，不新建并行状态机

复用 `PENDING→SUSPENDED→ADJUSTED→VERIFIED→RESOLVED`（ADR-0065），按需增补 `ADJUSTING / ADJUST_FAILED` 两态表达
brief 的 `COMPENSATING / COMPENSATION_FAILED` 语义。差异从 JSON 内嵌**提升为独立行表** `reconciliation_differences`，
但生命周期权威仍是 audit 那套——**同一事实不得有两套状态机**（State Ownership）。

### 决策 4：032 是 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 的**唯一产生方**

`sourceId` 确定性派生：`CS-{channelCode}-{period}` / `CF-{channelCode}-{period}`（渠道 + 周期粒度，与账单口径一致）。
**双计三道防线**：① 唯一键（Ledger 派生幂等键，ADR-0077 D4）；② 同一 `statement_import` 重放先作废旧事实再入新账
（差异驱动的 `ADJUSTMENT` 反向，不改写历史分录）；③ 账务侧 `CROSS_LEDGER_MISMATCH` / 试算平衡门禁兜底。

### 决策 5：结算事实口径 = 全部已确认事实 − 未收口差异净影响，且**必须先于**差异策略化

`settleableFacts` 的定义改造（C-13 修复）是 R4（差异处置策略化）的**前置**：
先让「未匹配的事实可见且不进结算」，再谈「差异如何处置」。顺序颠倒会让 R4 在错误的分母上做自动化决策。

### 决策 6：`PLATFORM_ONLY` 不改名；`DUPLICATE_INTERNAL` 不新建

`PLATFORM_ONLY` 语义（平台有、渠道无）在补入渠道事实后仍成立，改名收益不抵既有告警/看板/审计断言链断裂的代价。
「内部重复」由账务侧 `DUPLICATE_POSTING`（ADR-0077 幂等键 + 031 试算不平）承担，对账域不重复表达同一事实。

---

## 备选方案

- **A. 最小修复（只删 `sample.csv` 回退 + 补 `merchantId`）**：能消掉两个最危险的静默失败，但渠道资金事实仍不入账、
  差异仍不可查询、结算口径仍错——**不足以关闭 C-13/C-20/C-22**，否决。
- **B. 一步到位建「多源账单接入框架」**（SFTP / API / 对象存储 + 插件化解析器）：当前只有 CSV 一个真实来源，
  框架成本属预防性复杂度（Constitution §I「真实 > 全面」），否决；本 ADR 的 `StatementParser` 端口已为第二来源留口。
- **C. 采纳**：导入对象 + 三级匹配 + 复用差异生命周期 + 渠道事实入账 + 结算口径先行。

---

## Consequences

**正面**
- 对账第一次有**外部资金事实**做锚，`RealAuditor` 的两处静默跳过（`RealAuditor.java:65-77`）可改为产出差异；
- 跨商户串账与跨期重复结算（C-20）在地基上被关闭，032 的事实链与 031 的期间/分户模型对齐；
- 渠道实收与渠道成本进入账务 → 平台毛利（`FEE_REVENUE − CHANNEL_FEE_EXPENSE`）可被算出并可审计；
- 差异可查询、可分页、可重放，035 的 `reconciliation_pending` 积压指标才有分母。

**代价 / 风险**
- **跨服务 DTO 变更 = API Breaking**（四个 DTO 补 `merchantId`/`period`，`confirmed-facts` 加 `period` 入参）⇒ 需 §13 H11 裁决；
- 三张新表 + 若干非破坏性 ALTER，需按 033 的双路径可重放纪律交付（存量库不补列的坑 MUST NOT 重演）；
- `CHANNEL_FEE` 与账单手续费字段口径若与真实渠道不一致，会产生**新的常态差异类**（`FEE_MISMATCH`）——
  这是把隐藏问题显性化，不是引入问题，但需要运营侧准备处置手册（035 §14）；
- 结算口径改造若与 R4 同批实现，回归面大 ⇒ 本 ADR 明确要求**先口径、后策略**（决策 5）。

---

## 待人类裁决（详见 spec 032 §16）

| # | 决策项 | 边界类型 |
|---|---|---|
| H-032-1 | `confirmed-facts` 加 `period` 入参 + 出参 `merchantId` 全链使用 + `ReconciliationSummary` 加 `excludedFacts`（= design-review §13 **H11**） | 跨服务 API 变更 |
| H-032-2 | 批次唯一约束 `uk(period)` → `uk(channel_code, period, import_id)` + 三张新表 + 差异拆表 | Database Schema Migration |
| H-032-3 | 差异处置新增 `ADJUSTING` / `ADJUST_FAILED` 两态（**不接受**另建平行状态机，决策 3） | 状态机变更 |
| H-032-4 | 渠道账单成为 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 唯一产生方 + 周期级 `sourceId` 派生（决策 4） | 资金路径行为 + 幂等键口径 |
| H-032-5 | 是否引入「经 payment 渠道端口拉账单」（本轮不做，032-D 单独评估，决策 1） | 新增依赖 / 服务边界 |
| H-032-6 | 自动处置适用范围（推荐仅小额 `CHANNEL_ONLY` → 挂账；`AMOUNT_MISMATCH`/`FEE_MISMATCH` 一律人工） | 资金路径行为 |

> 另需一并裁决的相邻项：`payment_attempts` 是否加 `refund_no` 列（C-22 / design-review §13 **H19**，
> 由 spec 032 §8.3 的退款引用精确关联方案决定）、结算口径改造与 R4 的批次的顺序（决策 5，实施排期问题，非架构裁决）。
