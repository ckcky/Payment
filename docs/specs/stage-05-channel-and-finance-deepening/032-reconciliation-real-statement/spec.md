# Spec: 032-reconciliation-real-statement（对账真实化：渠道账单接入 + 事实维度补齐 + 渠道资金事实入账）

**Feature**：032　**标题**：Reconciliation & Real Statement
**版本**：v1.0（Draft，设计轮产物）　**日期**：2026-09-21
> **Status**: Implemented — 原「设计完成，待 Architecture Review 与负责人裁决（本轮只出设计，不改代码、不建 migration、不加测试）」为设计轮表述，已被后续实现取代：ADR-0080 已 Accepted（2026-09-21 负责人按 spec 推荐方案批准 H-032-1~6），2026-09-21 已实现并合入 master，acceptance.md 为回写补记 <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->
**前置**：030 已合入 master；031 已交付事件契约 / 两级科目 / 余额投影 / 期间（[spec 031](../031-ledger-accounting-foundation/spec.md)、[ADR-0077~0079](../../../adr/0077-ledger-accounting-foundation-decisions.md)）
**输入权威**：[stage-design §5](../stage-design.md)、[design-review §8 §9 §11 §12 附A](../design-review.md)

**阅读约定**（沿用 `stage-design.md`）：`【现状】`= 代码/Schema 已存在的事实（附 `path:line`）；
`【目标】`= 本 Feature 的设计意图，**尚未实现**；`【待确认】`= 属 Constitution §Governance 人类决策边界，
负责人裁决前不得被任何下游文档当作现行要求引用。

---

## 0. 定位与一句话目标

**把「对账」从「拿本地 CSV 假装对了一遍」升级为「以渠道账单为外部资金事实来源，可重复导入、可按商户与期间核对、可把渠道侧资金事实入账、差异可处置可审计」的真对账。**

四条主线（对应 design-review 的 C-13 / C-20 / C-22 / R1）：

1. **账单真实化**：渠道账单从 fixture 变为一等公民的**导入对象**（幂等、可追溯、解析失败即显式失败）；
2. **事实维度补齐**：对账与结算事实链补 `merchantId` + 期间，匹配键升级为 `(merchantId, reference)`；
3. **渠道资金事实入账**：账单里的「渠道实收净额 / 渠道手续费」成为 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 事件的**产生方**（031 §16 明确挂起、交由本 Feature 收口）；
4. **结算口径纠偏**：结算事实来源由「匹配成功的 `matches`」改为「全部已确认事实 − 未收口差异净影响」，消除 C-13 的静默漏结算。

**明确不做**（防止误读）：不做真实出款、不做「自动改单」（对账永不推进 Payment/Refund 状态）、不做规则 DSL、不做多币种清分。

---

## 1. Background：证据化的现状

### 1.1 渠道账单侧

| # | 【现状】 | 证据 |
|---|---|---|
| 1 | 账单获取只有一个端口 `ChannelStatementLoader.load(period)`，唯一实现是 CSV fixture 加载器（classpath `{statement-dir}/{period}.csv`，可选文件系统覆盖目录用于 E2E） | `reconciliation-service/src/main/java/com/payment/reconciliation/application/ChannelStatementLoader.java`、`infra/CsvChannelStatementLoader.java:37,71-86` |
| 2 | **未命中周期会静默回退 `sample.csv`**（仅 WARN + `reconciliation.statement_fallback` 指标），拿别的周期的账单当真账单对 | `CsvChannelStatementLoader.java:95-99` |
| 3 | 标准化行只有 **4 个字段**：`reference / amountMinor / currencyCode / status`——**无渠道、无商户、无费用、无发生时间、无类型、无唯一键** | `domain/ChannelStatement.java:11` |
| 4 | 畸形行（列数不足 / 金额非数字）**WARN 后跳过**，不产生差异、不阻断 | `CsvChannelStatementLoader.java:110-132` |
| 5 | 匹配是纯函数，**匹配键只有 `reference`**，先比金额再比状态 | `domain/ReconciliationMatching.java:26-58` |
| 6 | 差异类型 4 值：`AMOUNT_MISMATCH / STATUS_MISMATCH / PLATFORM_ONLY / CHANNEL_ONLY`；批次状态机 `PENDING→RECONCILING→CONSISTENT\|HAS_DIFFERENCE→PROCESSING→CLOSED` | `domain/DifferenceType.java:6-10`、`domain/ReconciliationStatus.java:10` |
| 7 | **重复导入不具备幂等能力**：幂等只到「同一 `period` 返回首次批次」，因此**同一周期更正后的账单永远无法重对** | `application/ReconciliationApplicationService.java:70-73,123-131` |
| 8 | 差异与匹配结果**内嵌 JSON 存进行**（`matches_json` / `differences_json`），差异行不可索引、不可分页查询 | `deployment/schema/07-reconciliation-schema.sql:8-27` |
| 9 | 账单来源字段 `ChannelStatementSource.sourceType` 把 SFTP / API 只写在注释里当「未来」，无任何实现 | `domain/ChannelStatementSource.java:8-12` |
| 10 | 全仓**没有任何 `statement_*` 表**——账单行只活在一次批次运行内 | `deployment/schema/*.sql` grep 结果 |

### 1.2 事实维度与审计侧

| # | 【现状】 | 证据 |
|---|---|---|
| 11 | **上游已经给出 `merchantId`，是 reconciliation 在边界处丢掉的**：`PaymentFactResponse` 已含 `merchantId`，而本地 `PaymentFactDto` / `SettlementBatchFact` 无该字段 | `payment-service/.../api/dto/PaymentFactResponse.java:12`、`reconciliation-service/.../infra/client/PaymentFactDto.java:7`、`audit/application/SettlementBatchFact.java:11` |
| 12 | **审计事实读取不带期间**：`AuditFactsGateway.confirmedFacts(period)` 对 payment / refund 两路**忽略 `period` 参数**（全量拉取） | `audit/infra/FeignAuditFactsGateway.java:42,48` |
| 13 | 账实核对（`RealAuditor`）对「账单行状态非 `SUCCEEDED`」「分录找不到对应事实」两种情况**静默跳过**，不产出差异 | `audit/application/RealAuditor.java:65-66,74-76` |
| 14 | 四核对的 11 类差异与 5 态处置闭环（`PENDING→SUSPENDED→ADJUSTED→VERIFIED→RESOLVED`）已可用，写账只经 ledger 的 `ADJUSTMENT` 事件通道 | `audit/domain/AuditDifferenceKind.java:5-27`、`audit/domain/AuditDifferenceStatus.java:8-18`、`audit/infra/FeignAuditLedgerGateway.java:35-45` |
| 15 | 结算批次来源是「对账 `matches`」——**未匹配上的事实永不结算**，且摘要只带 `matches` | `settlement-service/.../application/ReconciliationSummary.java:7`、`ReconciliationApplicationService.java:186-189` |
| 16 | 退款事实的渠道引用**取首条尝试**（无排序、无状态过滤）⇒ `reference` 可能指向失败尝试 | design-review C-22（`RefundFactsService`） |
| 17 | 031 已把 SETTLEMENT 事件 `sourceId` 收口为 `batchNo`，**M1（数值 ID 跨服务）已随 031 关闭**，本 Feature 不再重复规划 | `settlement-service/.../infra/client/FeignLedgerPostingGateway.java:38-55`、spec 031 §13 |
| 18 | 031 的 `LedgerPostingView` 在映射时**丢掉了 `period` / `postedAt` / `status`**，账务侧期间能力在对账侧不可用 | `audit/application/LedgerPostingView.java:14-16`、`audit/infra/FeignAuditFactsGateway.java:61-72` |

### 1.3 031 交给 032 的两件「挂起项」

- `CHANNEL_FEE` 与 `CHANNEL_SETTLEMENT` 两类事件在 031 **只有规则、没有产生方**（spec 031 §16 Non-Goals：「`CHANNEL_SETTLEMENT` 真实产生链路（032+）」）；
- 渠道费与商户费的双计风险由 031 显式登记为「产生方纪律 + **032 设计时复核**」（spec 031 §18 风险表）。

---

## 2. Problem（不修会怎样）

| 后果 | 触发条件 | 现状判定 |
|---|---|---|
| **假对账**：对上了不代表和渠道对上 | 周期未命中 ⇒ 回退 `sample.csv` | 🔴 已发生（证据 2） |
| **静默漏单**：账单坏行被跳过 = 差异消失 | 列数不足 / 金额脏 | 🔴 已发生（证据 4、13） |
| **跨商户串账**：不同商户同号事实互相抵掉 | 多商户 + `reference` 唯一匹配键 | 🟠 结构性缺陷（证据 5、11） |
| **跨期重复结算** | 事实读取不带期间 | 🟠 结构性缺陷（证据 12） |
| **更正账单无法重对** | 同周期重导入直接返回旧批次 | 🟠 已发生（证据 7） |
| **未匹配事实永不结算**：商户钱挂在应付 | 差异未收口即被排除出结算口径 | 🔴 资金滞留（证据 15） |
| **渠道实际收付不入账**：渠道应收科目余额永远等于交易口径推定值 | 无 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 产生方 | 🟠 账务不完整（§1.3） |

---

## 3. Goals / Non-Goals

### 3.1 Goals

- **G1**：渠道账单成为可追溯、可重复导入的**数据对象**（导入批次 + 标准化行落库 + 内容指纹幂等 + 解析失败显式失败）。
- **G2**：事实链补齐 `merchantId` 与期间维度，匹配键升级为 `(merchantId, reference)`；`RealAuditor` 的静默跳过全部转为可查询差异。
- **G3**：账单中的渠道侧资金事实入账——`CHANNEL_SETTLEMENT`（渠道实收净额到账）与 `CHANNEL_FEE`（渠道手续费成本）产生方落地，与 031 的科目/余额/期间闭环。
- **G4**：结算事实口径改造（C-13），使「已确认但未匹配」的事实不再被静默排除。
- **G5**：差异处置策略化（R4）：**只对可确定的差异类型**建立自动处置，且每次自动处置留 `audit_adjustments` 痕迹。
- **G6**：验收用例覆盖 design-review §12 附A 的 032 全部验证方式（含跨商户同号、跨期不重复结算、同账单重导不重复产生差异）。

### 3.2 Non-Goals（明确不做）

❌ 真实出款 / 银行对接 / 多币种清分 / 税费分账（stage-design §5.2 R3、design-review §14.3）
❌ 对账推进 Payment / Refund 状态机（**红线**，§7.5）
❌ 直接修改 `ledger_entries` 或历史 posting（只能发 `ADJUSTMENT`，§7.6）
❌ 规则 DSL / 可视化对账平台 / 差异自动核销引擎（策略表 = 代码枚举 + 配置开关，上限即此）
❌ 新建对账服务、引入新中间件（Kafka/S3/SFTP 客户端组件按需评估，默认落本地目录）
❌ 合并「基础渠道对账」与「会计四核对」两套模型为一套（§6.1 给出分工而非合并）
❌ 费率计算与商户签约费率模型（属 Pricing 方向，spec 031 §8 边界不变）

---

## 4. 核心业务模型：三方事实的一致性

```text
平台业务事实（payment/refund 已确认事实）        ← 平台「认为」发生了什么
        │  ①账证 / 账账（031 之后走事件与分录）
        ▼
账务事实（ledger postings / entries / balances）  ← 平台「记」了什么
        ▲  ③账实（本 Feature 真实化）
渠道账单事实（statement lines）                   ← 渠道「实际」发生了什么
```

**判据**：032 只做一件事——把③这条边从「CSV 走过场」变成「可信任的外部事实来源」，
并用它反哺①②缺的两类渠道侧事件（G3）。

**四类事实的正确关系**（写死，防后续误改）：

| 关系 | 口径 |
|---|---|
| 渠道账单 vs 平台事实 | 外部权威；**不一致时平台不自动改自己**，产出差异由处置策略/人工收口 |
| 平台事实 vs 账务 | 账务是平台的记账真相；「有事实无分录」= `MISSING_POSTING`（BLOCKER，结算门禁硬拦） |
| 渠道账单 vs 账务 | 032 起经 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 建立可比性（渠道应收按渠道分户对平） |
| 结算事实 | 只取**已确认**事实；口径见 §8（G4） |

---

## 5. 架构边界：reconciliation 不依赖任何渠道 SDK

Constitution 红线「核心领域不得依赖具体渠道实现」+ stage-design P1/P2 在本 Feature 的落地：

| 方案 | 描述 | 判定 |
|---|---|---|
| A | reconciliation 自己接渠道账单 API / SFTP | ❌ **否决**：把渠道 SDK 引入第二个服务，破坏 ADR-0076 R6 的端口收口，且账单解析与协议细节两处重复 |
| B | 账单**上传 + 落盘 + 解析归一**在 reconciliation 内（`StatementParser` 端口，格式实现按渠道分包） | ✅ **032 主路径**：零跨服务契约变更、零新依赖，真实账单与 fixture 走同一端口 |
| C | 经 payment-service 的 channel 层新增「账单下载」RPC，reconciliation 只拿标准化结果 | ⚠️ **032-D 评估**：渠道 SDK 仍收口在 payment-service（合规），但需新增跨服务契约 + 大文件传输方式 → 【待确认】H-032-5 |

**结论**：032 交付 B（`ChannelStatementLoader` 升级为「导入 + 解析 + 归一」三段），C 作为后续增强单列，不与本轮绑定。

---

## 6. Domain Model

### 6.1 两套核对的分工（不合并，收口交叠）

【现状】两套并存：基础渠道对账（`reconciliation_batches`）与会计四核对（`audit_batches` / `audit_differences`）。
【目标】**保持两套**，但把职责钉死，避免同一差异两处口径不一致：

| 家族 | 唯一职责 | 输入 | 本 Feature 的变化 |
|---|---|---|---|
| 基础渠道对账 | 渠道账单行 ↔ 平台已确认事实的**逐笔匹配台账** | 账单（G1）+ confirmed-facts | 匹配键升级、差异类型扩展、差异内嵌 JSON 拆表（§9） |
| 会计四核对 | 账证 / 账账 / **账实** / 账表的**借贷与归属一致性** | 事实 + 分录 + 账单 | 账实（`RealAuditor`）改用真实账单行 + 静默跳过转差异 |

**交叠点**：账单短款（有账单无平台事实）在基础对账 = `CHANNEL_ONLY`，在四核对 = `LEDGER_VS_STATEMENT_BREAK`。
【目标】两者**由同一次 `statement_import` 驱动**，`LEDGER_VS_STATEMENT_BREAK` 的 `sourceId` 指向差异记录号（可追溯），不重复计数（幂等键 `(kind, sourceType, sourceId)` 天然吸收）。

### 6.2 聚合与实体

- **`StatementImport`（新聚合）**：一次导入 = 一个渠道 + 一个周期 + 一份内容指纹；状态 `RECEIVED → NORMALIZED / REJECTED`；**不可变**（更正 = 新导入，见 §8.3）。
- **`StatementLine`（实体，归 `StatementImport`）**：标准化账单行——`channelCode / channelTxnNo / referenceType(PAYMENT\|REFUND\|SETTLEMENT\|FEE) / reference / merchantId / amountMinor / feeMinor / currency / status / occurredAt / rawText`。
- **`ReconciliationDifference`（新聚合，从 JSON 拆出）**：`diffNo / importId / batchNo / kind / severity / merchantId / reference / expected / actual / status / dispositionRef`；状态机见 §7。
- **`ChannelFundFact`（值对象）**：账单侧聚合出的渠道资金事实（实收净额 + 手续费），是 G3 两类事件的载荷来源。
- 【现状】`ReconciliationBatch` / `AuditBatch` / `AuditDifference` 保持；`ChannelStatement` 4 字段记录**被 `StatementLine` 取代**（迁移式，不留两套）。

---

## 7. State Machine

### 7.1 导入批次（新增，3 态，无终态回退）

`RECEIVED → NORMALIZED`（全部行解析成功）｜`RECEIVED → REJECTED`（任一必需字段缺失 / 表头不识别 / 币种非法 / 指纹重复但内容不一致）。
**`REJECTED` 是不可逆态**：同一周期改对账单 = 重新导入产生新批次，不就地修状态。

### 7.2 差异处置（复用既有 5 态，不新建第二套生命周期）

任务书建议的 `DETECTED → INVESTIGATING → RESOLVED / COMPENSATING / COMPENSATION_FAILED` **不引入新状态机**，
映射到已存在并有门禁/报表/结算联动的那一套（`audit/domain/AuditDifferenceStatus.java:8-18`）：

| 任务书语义 | 落地状态（复用） | 说明 |
|---|---|---|
| `DETECTED` | `PENDING` | 新建即待处置 |
| `INVESTIGATING` | `SUSPENDED`（挂账＝资金已入 `SUSPENSE` 科目，语义强于「调查中标记」） | 不引入「只改标记不动账」的空态 |
| `RESOLVED` | `VERIFIED`（recheck 通过）→ `RESOLVED`（人工确认收口） | 两态已区分「机器判定」与「人工结论」 |
| `COMPENSATING` / `COMPENSATION_FAILED` | `ADJUSTING`（瞬时）→ 成功 `ADJUSTED` / 失败 `ADJUST_FAILED` | 【待确认】H-032-3：处置动作与处置结果分两态；失败**留在台账可见**，不回退成 `PENDING` |

> 理由：新增一套并行生命周期会立刻制造「同一条差异两个状态」的漂移（违反 design-review §1.3 与 P6 文档即事实）。
> 若负责人要求字面一致的新状态机，属状态机变更（人类决策边界），需先裁决后改本节。

### 7.3 批次状态机

【现状】`PENDING→RECONCILING→CONSISTENT|HAS_DIFFERENCE→PROCESSING→CLOSED` **保持不变**。
【目标】放宽一处：`CLOSED` 批次允许「同周期新导入批次」并存（当前 `period` 单行唯一约束使其不可能，证据 7）——
`reconciliation_batches` 的唯一约束由 `uk(period)` 改为 `uk(channel_code, period, import_id)`。⚠️ Schema 变更 → H-032-2。

### 7.4 红线（MUST NOT）

- ❌ 任何差异处置路径**不得**调用 payment / refund / order 的写接口；
- ❌ 不得修改 `ledger_entries`、不得就地改 `posting`（更正只能新发 `ADJUSTMENT` 事件，append-only）；
- ❌ 不得把账单行金额直接写入平台事实表（账单是**外部事实**，比对而非覆盖）；
- ❌ 自动处置不得跨期、不得超累计差异额（`AdjustmentPolicy` 的「累计 ≤ 差异额」硬规则不变，`audit/domain/AdjustmentPolicy.java:49-79`）；
- ❌ CLOSED 期间不得写入任何事件（031 §11 的 `PERIOD_CLOSED` 拒绝优先于本 Feature 的一切补偿）。

---

## 8. 匹配、幂等与结算口径

### 8.1 匹配算法（分三级降级，全程可解释）

```
输入：platformFacts(period, merchantId?) × statementLines(importId)
① 键 (merchantId, referenceType, reference)   —— 双方都有商户维度，强匹配
② 键 (referenceType, channelTxnNo)            —— 真实渠道账单主键路径（平台侧经 attempts.channel_reference 还原）
③ 键 (merchantId, amountMinor, occurredAt±Δ)  —— 候选弱匹配：只产出 CANDIDATE 标记，不自动判定 MATCHED
```

- `reference` 归一化优先级：**渠道流水号 > 平台业务单号**（MOCK/历史账单允许用平台单号，显式标注 `referenceKind`）；
- 弱匹配③**只提建议不改判**：命中后差异仍为待处置态，处置记录里写候选依据（可审计）；
- 比对顺序：先 `amountMinor`，再 `feeMinor`（新增，§8.2），再 `status`；
- 商户缺失（账单不含商户且平台无法反查）→ 不猜，直接 `UNKNOWN_MAPPING`（§8.2）。

### 8.2 差异类型扩展（命名与既有值共存，不改名）

| 差异 kind | 状态 | 判定条件 |
|---|---|---|
| `MATCHED` | 【现状】 | 三级键任一命中且金额一致 |
| `AMOUNT_MISMATCH` | 【现状】 | 命中但金额不等 |
| `STATUS_MISMATCH` | 【现状】 | 命中但状态语义冲突 |
| `PLATFORM_ONLY` | 【现状】**保留原名**（= 任务书 `INTERNAL_ONLY`） | 平台有、账单无（**短款**） |
| `CHANNEL_ONLY` | 【现状】 | 账单有、平台无（**长款**） |
| `FEE_MISMATCH` | 【目标·新增】 | 本金一致而手续费不一致 |
| `DUPLICATE_CHANNEL` | 【目标·新增】 | 同一 `(importId, channelTxnNo, referenceType)` 出现多次 |
| `UNKNOWN_MAPPING` | 【目标·新增】 | 账单行无法归一（缺商户 / 缺键 / 类型不可识别）——**吸收现存的静默跳过**（证据 4、13） |
| `DUPLICATE_INTERNAL` | ❌ **不建** | 平台侧重复已由账务侧 `DUPLICATE_POSTING`（幂等键被击穿）承担，重复建会双报 |

> 命名纪律：`PLATFORM_ONLY` 不改名为 `INTERNAL_ONLY`——已入库差异快照、告警规则与门禁引用旧名（改名字面收益，属无谓破坏性变更）。

### 8.3 幂等（三层）

| 层 | 键 | 语义 |
|---|---|---|
| 导入 | `uk(channel_code, period, content_fingerprint)` | 同一账单重放 = 返回首次导入，**零新增差异** |
| 行 | `uk(import_id, line_no)` + 行内 `uk(channelTxnNo, referenceType)` | 同账单内重复行 → `DUPLICATE_CHANNEL`，不覆盖 |
| 差异 | `uk(period, kind, sourceType, sourceId)` | 重跑核对不产生第二条同类差异（沿用 017 语义） |

**更正账单的正确路径**（当前不可能，本轮收口）：新导入 → 新批次 → 与旧批次差异按 `(kind, sourceId)` 比对 →
旧差异置 `VERIFIED`（因新账单已一致）→ 新差异按需产生；**严禁**就地 UPDATE 旧账单行。

### 8.4 结算事实口径（G4，C-13 收口，**必须先行**）

【现状】`ReconciliationSummary` 只回 `matches` ⇒ 未匹配事实被静默排除出结算。
【目标】口径改为：

```
settleableFacts(period, merchantId) =
      全部已确认事实(period, merchantId)
    − 未收口差异（PENDING/SUSPENDED/ADJUSTING）所涉事实的净影响
```

- 门禁前置不变：`BLOCKER + PENDING` 或试算不平 ⇒ `AuditGate` 直接 BLOCK（fail-closed 现状保留）；
- **前置性**：G4 必须先于 G5（差异处置策略化）落地，否则自动处置会把「口径漏洞」放大成「静默漏结算 + 自动放行」；
- 契约变化：`confirmed-facts` 加 `period` 入参并按期间过滤、出参补 `merchantId`；`ReconciliationSummary` 出参补 `excludedFacts`（含原因），**跨服务 DTO 变更** → H-032-1。

---

## 9. Data Model（变更集，全部【目标】）

```sql
-- ① 账单导入批次（新表，reconciliation schema）
CREATE TABLE statement_imports (
  import_no VARCHAR(32) NOT NULL, channel_code VARCHAR(16) NOT NULL, period CHAR(7) NOT NULL,
  source_type VARCHAR(8) NOT NULL,            -- FILE（本轮）/ API（H-032-5）
  content_fingerprint CHAR(64) NOT NULL,      -- SHA-256(规范化字节流)
  row_count INT NOT NULL DEFAULT 0, status VARCHAR(12) NOT NULL, error_reason VARCHAR(255),
  imported_by VARCHAR(64) NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_import_no (import_no),
  UNIQUE KEY uk_import_identity (channel_code, period, content_fingerprint));

-- ② 标准化账单行（新表；原始行留档以便重解析与争议取证）
CREATE TABLE statement_lines (
  import_id BIGINT NOT NULL, line_no INT NOT NULL,
  channel_code VARCHAR(16) NOT NULL, channel_txn_no VARCHAR(64),
  reference_type VARCHAR(12) NOT NULL,        -- PAYMENT / REFUND / SETTLEMENT / FEE
  reference VARCHAR(64), reference_kind VARCHAR(16) NOT NULL,  -- CHANNEL_TXN / PLATFORM_NO / NONE
  merchant_id VARCHAR(32), amount_minor BIGINT NOT NULL, fee_minor BIGINT NOT NULL DEFAULT 0,
  currency CHAR(3) NOT NULL, status VARCHAR(16) NOT NULL, occurred_at DATETIME,
  raw_text TEXT NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_line (import_id, line_no),
  UNIQUE KEY uk_channel_txn (import_id, channel_txn_no, reference_type),  -- channel_txn_no 为空时由生成列兜底
  KEY idx_line_ref (reference_type, reference, merchant_id));

-- ③ 差异从 JSON 拆表（新表；历史 JSON 只读保留，脚本回填）
CREATE TABLE reconciliation_differences (
  diff_no VARCHAR(32) NOT NULL, batch_id BIGINT NOT NULL, import_id BIGINT,
  period CHAR(7) NOT NULL, merchant_id VARCHAR(32), channel_code VARCHAR(16),
  kind VARCHAR(24) NOT NULL, severity VARCHAR(8) NOT NULL,
  reference_type VARCHAR(12), reference VARCHAR(64),
  expected_amount_minor BIGINT, actual_amount_minor BIGINT, fee_amount_minor BIGINT,
  currency CHAR(3) NOT NULL, status VARCHAR(16) NOT NULL,
  disposition_ref VARCHAR(32),                -- 关联 audit_adjustments.adjust_no / 处置单
  created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_diff_no (diff_no),
  UNIQUE KEY uk_diff_identity (period, kind, reference_type, reference),
  KEY idx_diff_status (status, period));

-- ④ 既有表的非破坏性变更
ALTER TABLE reconciliation_batches ADD channel_code VARCHAR(16) NOT NULL DEFAULT 'MOCK',
    ADD import_id BIGINT NULL;                 -- 唯一约束 uk(period) → uk(channel_code, period, import_id)【H-032-2】
ALTER TABLE audit_differences ADD import_id BIGINT NULL;  -- 账实核对可回溯到账单行
```

- 迁移式口径与 031 一致：`differences_json` **停写不停读**（历史批次快照仍可查），新代码只写 `reconciliation_differences`；
- 所有脚本走**空库 / 存量库双可重放**（`information_schema` 守卫 + `PREPARE`，模式见 `018-schema-normalization.sql`；禁用 MariaDB `IF NOT EXISTS` 方言——033 的可重放门禁未立前按现有先例执行）。

---

## 10. API / Event 契约（【目标】）

### 10.1 入站（reconciliation）

| 端点 | 用途 | 备注 |
|---|---|---|
| `POST /internal/reconciliation/statement-imports` | 上传/登记账单（`channelCode, period, sourceType, contentRef 或 body`） | 幂等：同指纹重放返回首次 `importNo`；内部服务令牌守卫 |
| `GET /internal/reconciliation/statement-imports?period=&channelCode=` | 导入台账查询 | 含 `status / rowCount / rejectedReason` |
| `POST /internal/reconciliation/run {period, channelCode, importNo?}` | 执行核对（缺省取该渠道该周期最新 `NORMALIZED` 导入） | **不再有 `sample.csv` 回退**：无可用导入 ⇒ 400 `STATEMENT_UNAVAILABLE` |
| `GET /internal/reconciliation/differences?period=&status=&merchantId=&kind=` | 差异分页查询（拆表后能力） | 替代读 JSON 的隐性契约 |
| `POST /internal/reconciliation/differences/{diffNo}/resolve` | 人工收口（必填备注） | 沿用 `Difference.resolve` 纪律（备注非空） |
| `POST /internal/reconciliation/channels/{channelCode}/fund-facts/{period}/post` | 触发渠道资金事实入账（G3） | **幂等**：`(CHANNEL_SETTLEMENT, channelCode+period)` |

### 10.2 出站事件（032 成为 031 两类事件的首个产生方）

```
POST /internal/ledger/accounting-events        （spec 031 §6.2 契约，不改形状）
  CHANNEL_SETTLEMENT  { sourceId: "CS-{channelCode}-{period}", channelCode, netReceivedMinor, currency }
      → 借 BANK_CASH / 贷 CHANNEL_RECEIVABLE(channelCode)          （031 §7.4 规则）
  CHANNEL_FEE         { sourceId: "CF-{channelCode}-{period}", channelCode, feeMinor, currency }
      → 借 CHANNEL_FEE_EXPENSE / 贷 CHANNEL_RECEIVABLE(channelCode) （031 §7.3 规则）
```

**双计防线**（复核并**收口** spec 031 §18 登记的 CHANNEL_FEE 风险）：

1. `sourceId` 由「渠道 + 周期」**确定性派生**，同一账单更正重放经 `uk_event_source` 天然吸收；
2. 渠道费**唯一合法来源 = 渠道账单**；禁止任何交易链路组件上报 `CHANNEL_FEE`（ArchUnit 词汇边界 + `AccountingVocabularyBoundaryTest` 同族规则守住「谁能发哪类事件」）；
3. 若账单显示渠道费与平台推定不一致 ⇒ 产 `FEE_MISMATCH` 差异，由 `ADJUSTMENT` 收口，**不得**补发第二条 `CHANNEL_FEE` 抵差。

### 10.3 上游契约变更（H-032-1）

| DTO | 变更 | 破坏性 |
|---|---|---|
| `PaymentFactResponse` / `RefundFactResponse` | 已有 `merchantId`；请求侧加 `period`（缺省 = 全量，兼容一个迭代） | 新增入参：向后兼容 |
| `PaymentFactsRequest`（新） | `period`、`merchantId?` | — |
| reconciliation 本地 `PaymentFactDto` / `SettlementBatchFact` | 补 `merchantId`（**只是不丢字段**，无跨服务影响） | ❌ 非破坏 |
| `LedgerPostingView` | 补 `period` / `postedAt` / `status`（031 出参已有） | ❌ 非破坏 |
| `ReconciliationSummary`（settlement 侧镜像） | 加 `excludedFacts[{reference,reason}]`；`matches` 语义不变 | 新增字段：兼容 |
| `confirmed-facts` 端点 | 加 `period` 查询参数 | 参数可选，兼容 |

---

## 11. Failure Handling（分类 + 处置 + 出口）

| # | 故障 | 处置 | 谁负责 | 出口 |
|---|---|---|---|---|
| 1 | 账单文件缺失 / 周期不匹配 | **显式失败 400**，指标 `reconciliation.statement_unavailable{channel,period}` | 运维/商户运营 | 上传正确账单后重跑 |
| 2 | 账单解析失败（表头/编码/字段） | 整批 `REJECTED` + 行号 + 原因；**不产生半套差异** | 对账运维 | 重新导入 |
| 3 | 账单部分行无法归一 | 行级 `UNKNOWN_MAPPING` 差异，批次继续（可解释、可查询） | 人工处置 | 收口或改解析器 |
| 4 | 上游事实读取失败（RPC） | 保留 `reconciliation.fact_read_failed{target}`；**当次核对不产出差异**（防把「读不到」误判成「不存在」） | 系统 | 重试 |
| 5 | 渠道资金事实入账失败 | 记 `ledger.posting_failed`；**入账台账可查可重放**（034 的失败台账模式；031 §12 `pending_postings` 在 031 代码里未实现，本 Feature 不得假设其存在） | 034 收口 + `MISSING_POSTING` 兜底 | 幂等重放 |
| 6 | 同周期重复导入同一账单 | 幂等返回首次结果，不重对、不新增差异 | 系统 | — |
| 7 | 同周期导入不同账单 | 新导入批次并存，旧差异按 §8.3 收口 | 系统 + 人工确认 | 差异状态变化留审计 |
| 8 | 关账后仍需更正（031 §11 CLOSED） | `PERIOD_CLOSED` 拒绝；更正写进**下一期间**的 `ADJUSTMENT` | 人工 | 下期反向分录 |
| 9 | 处置动作失败（挂账/调账 RPC 异常） | 差异保持原态 + 记 `ADJUST_FAILED`（H-032-3），**不静默吞异常** | 人工 | 重新处置 |

---

## 12. Observability（本 Feature 需要的最小指标，落地由 035 收口）

| 指标 | 维度 | 业务含义 |
|---|---|---|
| `reconciliation.statement_import{channel,result}` | `result=accepted\|rejected\|duplicate` | 账单导入是否可用、是否重复 |
| `reconciliation.statement_unavailable{channel,period}` | 计数 | **替代**现 `statement_fallback`（后者随静默回退一起退役） |
| `reconciliation.difference{kind,severity}` | kind 8 值 | 差异分布（现有，扩展 kind） |
| `reconciliation.unmatched_amount_minor{currency}` | 累计 | 未收口的资金敞口（金额，非单号） |
| `reconciliation.autodisposition{policy,outcome}` | `outcome=succeeded\|failed` | 自动处置可审计性（G5 硬要求） |
| `ledger.channel_fund_posting{channel,eventType}` | 计数 | 渠道资金事实是否入账（G3 唯一健康度信号） |

**高基数纪律**（Constitution §Obs；035 立策略）：以上标签全部低基数；**`merchantId` / `diffNo` / `importNo` / `paymentNo` 一律不得作为指标标签**，只进日志与差异台账。

日志：`FINANCIAL_AUDIT` 现有 `StructuredAuditLogger` 动作集扩展 `statement_import` / `difference_autodispose` / `channel_fund_post`（`docs/guides/engineering-standards.md`）。

---

## 13. Testing（本 Feature 自己维护的业务断言；基础设施归 033）

| 用例 | 断言 | 来源 |
|---|---|---|
| TC-032-1 正常全匹配 | 账单与事实一致 ⇒ 批次 `CONSISTENT`、零差异、结算可放行 | 任务书 §六 |
| TC-032-2 金额不符 | `AMOUNT_MISMATCH` ×1，`expected/actual` 精确到分 | 任务书 §六 |
| TC-032-3 渠道长款 | `CHANNEL_ONLY`；同一次运行内 `LEDGER_VS_STATEMENT_BREAK` 不双计 | §6.1 交叠 |
| TC-032-4 平台短款 | `PLATFORM_ONLY`（不改名）；差异为 BLOCKER 时结算门禁 BLOCK | 任务书 §六 |
| TC-032-5 平台 `UNKNOWN` / 渠道 `SUCCESS` | 对账**只产 `STATUS_MISMATCH` 差异**，Payment 状态零改动；人工 `resolve` 后才收敛 | 任务书 §六 + §7.4 |
| TC-032-6 跨商户同号 | 两商户同一 `reference` ⇒ 各自独立匹配、各自净额，不互相抵掉 | C-20/G2 |
| TC-032-7 同账单重放 | 二次导入同指纹 ⇒ 新差异 0、新行 0、批次不变 | G1 |
| TC-032-8 更正账单 | 旧批次差异可收口、新批次差异正确，且原始账单行不被就地修改 | §8.3 |
| TC-032-9 跨期不重复结算 | 连续两期同一事实 ⇒ 第二期不重复计入 | C-20 |
| TC-032-10 渠道到账入账 | `CHANNEL_SETTLEMENT` + `CHANNEL_FEE` 分录平衡、渠道应收归零、试算平衡表对平 | G3 |
| TC-032-11 未匹配事实不漏结算 | 构造「挂账后放行」⇒ 结算事实集合仍含该事实（只扣净影响） | C-13 |
| TC-032-12 退款引用精确 | 先失败后成功的双尝试 ⇒ 事实 `reference` 指向**成功**尝试流水 | C-22 |
| TC-032-13 坏行不静默 | 缺商户 / 缺键 / 类型不可识别 ⇒ 每行一条 `UNKNOWN_MAPPING`，条数与账单行数可对账 | §8.2 |
| TC-032-14 关账拒写 | 期间 CLOSED 时补发渠道事实 ⇒ `PERIOD_CLOSED`，无分录 | §7.4 |

**真库并发 / 唯一键断言依赖 033**（`uk_import_identity`、`uk_diff_identity` 在 MySQL 上的真实行为）；
033 未落地前，上述断言在 H2 上的通过**不得**被当作唯一约束验证通过（沿用 spec 031 §18 同一条口径）。

---

## 14. Acceptance Criteria（可勾选）

- [ ] AC-1 无 `sample.csv` 静默回退路径（代码层删除，指标退役），账单不可用一律显式失败；
- [ ] AC-2 `statement_imports` / `statement_lines` / `reconciliation_differences` 三表落地，差异可按状态/商户/周期分页查询；
- [ ] AC-3 匹配键为 `(merchantId, referenceType, reference)`，`merchantId` 全链有值（含 `confirmed-facts` 出参与 `ConfirmedFactGate` 商户校验）；
- [ ] AC-4 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 有真实产生方，且双计防线（§10.2 三条）各有测试或门禁；
- [ ] AC-5 结算事实口径 = 全部已确认事实 − 未收口差异净影响，且 G4 先于 G5 合入；
- [ ] AC-6 自动处置每次留 `audit_adjustments` 痕迹（可回溯到 diffNo + policy）；
- [ ] AC-7 TC-032-1 ~ 14 全部落地并通过；`mvnw clean verify` 全绿；
- [ ] AC-8 L0 文档同步（`systems/reconciliation-service.md`、`systems/settlement-service.md`、`technical-solution.md` 对账节），无漂移；
- [ ] AC-9 空库 + 存量库双侧重放一致（脚本级自检）。

---

## 15. 交付切分（032 内部分批，每批独立 PR）

| 批 | 范围 | 依赖 | 边界裁决 |
|---|---|---|---|
| **032-A 事实维度** | G4 结算口径（C-13）+ `merchantId`/期间补齐 + C-22 退款引用精确化（H13）+ `LedgerPostingView` 补字段 | **必须先于 032-B/C/D** | H-032-1 |
| **032-B 账单真实化** | G1 导入聚合 + 三表 + 幂等 + fail fast + 差异拆表 + 静默跳过转差异 | 032-A | H-032-2 |
| **032-C 渠道资金事实** | G3 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 产生 + 账实核对升级 + 双计防线 | 031 合入；032-B | H-032-4 |
| **032-D 处置策略化** | G5 策略表 + 自动处置审计 + （可选）账单 API 拉取（方案 C） | 032-A/B；034 失败台账（可选） | H-032-3、H-032-5 |

---

## 16. 人类决策边界（MUST 负责人裁决后生效）

| # | 事项 | 边界类型 | 推荐 |
|---|---|---|---|
| **H-032-1** | `confirmed-facts` 加 `period` 入参 + 出参 `merchantId` 全链使用 + `ReconciliationSummary` 加 `excludedFacts` | 跨服务 API 变更（= §13 H11） | 执行；`period` 缺省全量、保留一个迭代的兼容窗口 |
| **H-032-2** | `reconciliation_batches` 唯一约束 `uk(period)` → `uk(channel_code, period, import_id)` + 三新表 + 差异拆表 | Database Schema Migration | 执行（否则「更正账单」不可能） |
| **H-032-3** | 差异处置新增 `ADJUSTING` / `ADJUST_FAILED` 两态（`ADJUST_FAILED` 不计入「未收口」但必须可见） | 状态机变更 | 采纳；**不接受**另建 `INVESTIGATING/COMPENSATING` 平行状态机 |
| **H-032-4** | 渠道账单成为 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 的唯一产生方；周期级 `sourceId` 派生格式 `CS-{channel}-{period}` | 资金路径行为 + 幂等键口径 | 执行（031 §16 挂起项在此收口） |
| **H-032-5** | 是否引入「经 payment 渠道端口拉账单」（新增跨服务 RPC，可能新增 SFTP/HTTP 依赖） | 新增依赖 / 服务边界 | **本轮不做**，032-D 单独评估 |
| **H-032-6** | 自动处置适用范围（推荐仅「小额 `CHANNEL_ONLY` → 挂账」；`AMOUNT_MISMATCH` / `FEE_MISMATCH` 一律人工） | 资金路径行为 | 保守起步，策略每加一条须同时加测试与指标 |

> **裁决记录（2026-09-21）**：负责人裁决 **H-032-1~H-032-6 全部按本表推荐方案批准**；[ADR-0080](../../../adr/0080-reconciliation-statement-and-fund-facts.md) 同日转 🟢 Accepted，Feature 032 随即实现落地（见 roadmap 与 CHANGELOG）。相邻项收口口径：C-22 退款渠道引用以**查询侧精确化**落地（仅取 SUCCEEDED 退款渠道尝试、id 降序确定性排序），`payment_attempts` **未**加 `refund_no` 列；结算口径改造（决策 5）先于差异策略化同 Feature 交付。

---

## 17. 与其它 Feature 的关系（禁止项写死）

| 关系 | 约束 |
|---|---|
| 031 → 032 | 032 **只使用** 031 的事件契约与余额/期间视图；**不得**在 reconciliation 侧组装分录或指定科目（031 已把决策权收口，越界即回归 P0） |
| 032 → 031 | 032 **不得**要求改 `AccountingEventRequest` 形状来适配账单；缺字段先扩展 032 侧标准化 |
| 032 ↔ 034 | 032 的失败重放复用 034 的**统一失败台账**；032 不得自建私有重试表（031 §12 的模式为准） |
| 032 ↔ 033 | 唯一约束/并发/双库重放的验证归 033；032 只写业务断言 |
| 032 ↔ 035 | §12 指标由 035 统一命名、看板与告警；032 不自定告警规则 |
| 030 → 032 | 账单里的 `channelCode` 必须与 030 落库模态一致口径（`SANDBOX` 账单与 `MOCK` 账单不得混对） |

---

## 18. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 真实账单字段与 §6.2 标准化模型不匹配（尤其支付宝账单按「业务操作号」而非平台单号） | 解析器被迫塞特例 ⇒ 边界腐蚀 | 032-B 前用**一份真实账单样本**做归一化走查（design-only 任务，本表列前置） |
| 差异拆表迁移期双读 | 新老批次展示口径不一致 | `differences_json` 明确「停写不停读」，读路径单点封装 |
| G4 口径变更使结算放行更多 | 漏结算消失但误放行风险上升 | 保留 BLOCKER+PENDING fail-closed；`excludedFacts` 强制出现在响应与审计里 |
| 渠道费双计（031 登记未决） | 重复记成本 ⇒ 渠道应收不平 | §10.2 三条防线 + TC-032-10/13 |
| 自动处置放大数据错误 | 批量错账 | 起步只允许「小额挂账」；`ADJUST_FAILED` 可见；金额上限走配置且默认 0（= 关闭） |
| 031 未合入 master | 032 依赖的事件契约与余额视图仅存在于工作区 | **031 先合**是 032 开工的硬前置（roadmap 依赖已登记） |

---

## 19. 文档同步清单（实现轮执行，本轮仅登记）

`docs/architecture/systems/reconciliation-service.md`（账单导入聚合 / 差异表 / 匹配键）、
`systems/settlement-service.md`（结算事实口径）、`systems/payment-service.md`（`confirmed-facts` 入参）、
`technical-solution.md` 对账节与数据流、`roadmap.md` 状态、`docs/adr/0080-*`（§20 决策）。

---

## 20. 架构决策摘要（→ 建议新立 ADR-0080）

- **决策 1**：账单成为**导入对象**（`statement_imports` + `statement_lines` + 内容指纹幂等），渠道账单的获取留在 reconciliation 的 `StatementParser` 端口之后，**不引入渠道 SDK**（否决方案 A/C 的理由见 §5）。
- **决策 2**：匹配键升级为 `(merchantId, referenceType, reference)`，三级降级匹配，弱匹配只出候选不改判。
- **决策 3**：差异处置**复用**四核对 5 态状态机（+ `ADJUSTING/ADJUST_FAILED`），**不新建并行生命周期**。
- **决策 4**：032 是 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 的**唯一产生方**，`sourceId` 按「渠道 + 周期」确定性派生，双计防线三条（§10.2）。
- **决策 5**：结算事实口径改为「全部已确认事实 − 未收口差异净影响」，且此改造**必须先行**于差异策略化。
- **决策 6**：`PLATFORM_ONLY` 不改名；`DUPLICATE_INTERNAL` 不建（由账务侧 `DUPLICATE_POSTING` 承担）。

→ 待负责人裁决 §16 后转 🟢 Accepted，落点：`docs/adr/0080-reconciliation-statement-and-fund-facts.md`。
