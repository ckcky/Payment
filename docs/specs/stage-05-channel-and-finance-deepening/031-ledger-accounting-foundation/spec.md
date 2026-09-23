# Spec: 031-ledger-accounting-foundation（Ledger / Accounting 地基：Accounting Event + Posting Rule + 两级科目）

**Feature Branch**: `031-ledger-accounting-foundation`

**Created**: 2026-09-21

> **Status**: Implemented — 原「Draft v1.0／§17 须先裁决再进 `/speckit-plan`」为设计轮表述，已被后续收口取代：ADR-0077~0079 经负责人 D-1~D-7 批准（Accepted），2026-09-21 已实现并合入 master（`mvnw verify` 866 tests 全绿；Plan/Tasks/Acceptance 为回写补记） <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->

**输入**: Ledger/Accounting 架构审计结论（2026-09-21，基于 030 合并后的真实代码与 stage-05 两份文档）+ 负责人对业务模型的锁定（平台代商户收款）与编号裁决（本 Feature 编号 **031**，stage-design 旧名 `032-ledger-account-view` 作废）。

---

## 0. 定位、取代关系与编号勘误

### 0.1 一句话目标

把 ledger-service 从「**上游组装原始分录、账本只做平衡校验**」收敛为「**上游只发 Accounting Event / Financial Fact、账本经 Posting Rule 决定怎么记**」，并在此地基上交付科目分户余额视图、期间试算平衡与待记账清单——让系统从「有分录」走向「有账」。

### 0.2 编号勘误（docs-only，随本 Spec 一并生效）

| 文档 | 旧编号 | 本 Spec 裁定（负责人 2026-09-21） |
|---|---|---|
| `stage-design.md` §9.2/§9.3 | `032-ledger-account-view` | **`031-ledger-accounting-foundation`**（编号 + 名称） |
| `stage-design.md` §9.2 其余行 | `033-reconciliation…` ~ `036-observability…` | 顺移为 `032` ~ `035`（与 `design-review.md` §12 拆分表对齐） |
| `design-review.md` §12 | 已按 031=账务 排列 | 维持，仅名称随之更新 |

### 0.3 与既有文档 / ADR 的关系（迁移式，不并存两套模型）

- **Partially Supersedes ADR-0008**（「分录由调用方组装」与「固定 5 科目枚举」两条）；`Posting`+`LedgerEntry` 复式结构与平衡门禁**全部保留**。载体：ADR-0077 / ADR-0078。
- **修订 ADR-0009 / ADR-0018 / ADR-0023 的契约描述**（触发时机、失败不回滚、同步 RPC 三条语义**不变**，变的是「传什么」）。载体：ADR-0077。
- **扩展 ADR-0011**：引入渠道清算**科目**（CHANNEL_RECEIVABLE / BANK_CASH / CHANNEL_FEE_EXPENSE）与事件契约；渠道清算**链路**（真实账单驱动 CHANNEL_SETTLEMENT 产生）仍不做，留 032+。
- **吸收 design-review 存量项**：C-09/H2（G3 待记账清单）、C-07/H3（负净额结算反向分录）、M1（`SETTLEMENT` sourceId 数值 batchId → `batchNo`，随事件化天然收编）。
- **H11 前置收编**：payment 事实补 `merchantId`（`confirmed-facts` 出参）本应由 032 做，但 031 的 PAYMENT_CAPTURE 事件**必须有 merchantId 才能解析商户应付账户**——故「payments 表加 `merchant_id` 列 + 事实出参补该字段」提前到 031（一次加列，032 不再重复动）。
- `docs/architecture/systems/ledger-service.md` 与 `technical-solution.md` 描述**当前事实**，本 Spec 的目标态在其实现完成前 MUST NOT 回写（stage-design 附录纪律）。

---

## 1. Background（审计结论摘要，证据见括号）

1. **记账决策权在上游（P0）**：`PostingRequest` 接受调用方直传 `accountId + direction`；payment / refund / settlement / audit 四方网关各自硬编码科目 ID 常量与借贷方向（`FeignLedgerPostingGateway.java:27-29` 等四处）。科目调整需改 4 个服务，Ledger 退化为「带平衡校验的存储」。
2. **毛/净轧差在调用方（P0）**：`netMinor = amountMinor - feeMinor` 发生在 payment 网关内。
3. **账户无 Instance 维度（P1）**：`MERCHANT_PAYABLE` 全局一条，无法回答「欠 M001 多少」——C-20/N1 跨商户串账的账务侧根因。
4. **手续费链路从未闭环（P1）**：两个支付记账调用点恒传 `feeMinor = 0L`（`PaymentApplicationService.java:264`、`PaymentResultProcessor.java:193`）；`A = N + F` 模板只在 F=0 时被验证过；全仓无任何费率模型。
5. **渠道资金语义缺失（P1）**：支付成功记 `Dr CUSTOMER_CASH`，把「钱还在渠道、未清算」当成「已到账平台银行户」；无 CHANNEL_RECEIVABLE / BANK_CASH / CHANNEL_FEE_EXPENSE。
6. **幂等键口径靠约定（P1）**：无 `uk(event_type, source_id)`；历史双前缀缺陷（design-review Q2）证明约定必被破坏。
7. **无「账」的视图（G1~G3）**：余额靠全表内存聚合（`BalanceChecker.java:41-52`）且无端点；无期间/试算平衡/关账；记账失败只记指标无载体。

---

## 2. 核心业务模型：平台代商户收款

用户支付 100 元（支付宝，渠道费率 0.6%，平台对商户签约费率 0.8%）：

**平台并不天然拥有这 100 元**。支付成功瞬间形成的是——

- 平台对**渠道**的应收（钱还在支付宝：`CHANNEL_RECEIVABLE:ALIPAY`）
- 平台对**商户**的应付（钱最终属于商户：`MERCHANT_PAYABLE:M001`）

| 金额概念 | 值 | 归属域（谁计算） | 语义 |
|---|---|---|---|
| Gross Amount | 100.00 | 支付事实 | 用户实付 |
| Merchant Fee | 0.80 | **上游**（商户签约费率） | 平台向商户**收取**的收入 |
| Channel Fee | 0.60 | **上游**（渠道协议费率） | 平台向渠道**支付**的成本 |
| Merchant Net | 99.20 | = Gross − Merchant Fee | 商户实收口径 |
| Channel Net Receivable | 99.40 | = Gross − Channel Fee | 渠道清算时应付平台的净额 |
| Platform Gross Margin | 0.20 | = Merchant Fee − Channel Fee | 平台毛利（报表口径，不记账） |

> **Merchant Fee ≠ Channel Fee**：一个是收入、一个是成本，从科目到字段强制分离，禁止轧进同一净额（现状缺陷 2 的根治）。

**案例六终态（031 验收断言，全部按币种 CNY）**：

```text
CHANNEL_RECEIVABLE:ALIPAY   借余 99.40   （100.00 − 0.60，等支付宝清算给我们）
MERCHANT_PAYABLE:M001       贷余 99.20   （100.00 − 0.80，我们欠商户的）
FEE_REVENUE                 贷余  0.80   （平台收入）
CHANNEL_FEE_EXPENSE:ALIPAY  借余  0.60   （平台成本）
```

---

## 3. 核心架构原则（MUST，写入设计即约束）

1. **Payment 不感知具体会计科目**——上游请求体中不出现 `accountId` / `accountCode`（ADJUSTMENT 的语义字段例外，见 §7.6）。
2. **Payment 不创建 LedgerEntry**——分录只在 Ledger 内部生成。
3. **Payment 不决定 Debit / Credit**——方向由 Posting Rule 依据科目正常余额方向推导。
4. **Ledger 不负责业务费率计算**——不查商户费率、不查渠道费率、不调 Pricing、不算百分比。
5. **Ledger 根据 Accounting Event / Financial Fact 选择 Posting Rule**——上游只陈述「发生了什么」。
6. **Posting Rule 负责定义会计意义**——规则即代码 Strategy，一事件类型一规则。
7. **AccountResolver 负责把抽象账户解析为具体账户**——`(definitionCode, ownerKey, currency) → Account Instance`。
8. **LedgerEntry 是不可变财务事实**——append-only；更正走新 ADJUSTMENT 事件，禁 UPDATE/DELETE（现状已合规，保留）。
9. **Balance 是 Projection，不是 Source of Truth**——事实源恒为 `ledger_entries`；余额表可校验、可重建。
10. **所有 Posting 必须幂等**——幂等键由 Ledger 按 `{eventType}:{sourceId}` 派生 + 双唯一约束，重复事件永不产生重复分录。

一句话：**上游告诉账务「发生了什么」，账务系统决定「应该怎么记」。**

---

## 4. 目标架构管道

```text
payment / refund / settlement / reconciliation-audit        （业务域：状态 + 已确认财务事实）
        │  POST /internal/ledger/accounting-events
        ▼
AccountingEventRequest ──► 幂等派生键 {eventType}:{sourceId} ──► 回查命中 ⇒ 回放首次结果
        ▼
PostingRuleRegistry ──► PostingRule（每 eventType 一个 Strategy）
        ▼
List<PostingLine>（definitionCode + ownerKey + 语义金额槽）
        ▼
AccountResolver（definitionCode + ownerKey + currency → Account Instance，缺户按 §5.4 策略）
        ▼
PostingEngine ──► Posting 聚合根（构造期借贷平衡门禁，继承现状不变量）
        ▼        └─► 同一 DB 事务：ledger_transactions(postings) + ledger_entries + account_balances
   LedgerTransaction + LedgerEntry[]（不可变）+ Balance Projection（G1）+ period 派生/关账门禁（G2）
```

**组件职责**（全部代码 Strategy，无 DSL、无规则表、无热加载）：

| 组件 | 职责 | 落位 |
|---|---|---|
| `AccountingEvent` | 已确认财务事实的入站契约 | `common-dto`（新）+ ledger `api` 装配 |
| `PostingRule` | 接口：`eventType()` + `expand(event, resolver) → List<PostingLine>`；金额轧差、方向、科目全部在此 | ledger `domain/posting/` |
| `PostingRuleRegistry` | 启动期注册；断言每个 eventType 恰有一条规则、规则输出的 definitionCode 全部可解析（配错不静默，fail fast） | ledger `domain/posting/` |
| `PostingLine` | 值对象：`(definitionCode, ownerType, ownerId, direction, amountMinor)`——**仅存在于 Ledger 内部**，不再出现在任何 RPC 契约 | ledger `domain/posting/` |
| `AccountResolver` | 抽象账户 → Account Instance（含开户策略 §5.4） | ledger `application/` |
| `PostingEngine` | 事件 → 规则 → 解析 → 聚合根 → 单事务持久化（+ 投影 + 审计，沿用现有 `StructuredAuditLogger` / 指标） | ledger `application/` |

**保留不动**：`Posting` 聚合根及构造期平衡门禁、append-only 分录、先查后插 + `DuplicateKeyException` 回查、同步 RPC + 失败不回滚 + UNKNOWN 不记账、`long amountMinor + currency`（ADR-0010）、业务单号 sourceId（ADR-0063）。

---

## 5. Account Model（Definition / Instance 两级，ADR-0078）

### 5.1 Account Definition（科目定义——「账户类型」）

`Account` 由 Java 枚举升级为受版本控制的表（seed 随 `09-ledger-schema.sql` 重放，新增科目 MUST 走 ADR——G4 纪律不变）：

| code | type | 正常余额 | owner 维度 | 说明 |
|---|---|---|---|---|
| `CHANNEL_RECEIVABLE` | ASSET | DEBIT | CHANNEL | 平台对渠道的应收，按渠道分户 |
| `MERCHANT_PAYABLE` | LIABILITY | CREDIT | MERCHANT | 应付商户，按商户分户 |
| `BANK_CASH` | ASSET | DEBIT | PLATFORM | 平台银行现金（渠道清算到账 / 未来出款） |
| `FEE_REVENUE` | REVENUE | CREDIT | PLATFORM | 平台向商户收取的手续费收入 |
| `CHANNEL_FEE_EXPENSE` | EXPENSE | DEBIT | CHANNEL | 平台向渠道支付的成本（按渠道分户） |
| `SETTLEMENT_PAYABLE` | LIABILITY | CREDIT | MERCHANT | 已结算待出款（MVP 不出款） |
| `SUSPENSE` | ASSET | DEBIT | PLATFORM | 待处理差错款（017 语义不变） |
| `CUSTOMER_CASH` | ASSET | DEBIT | PLATFORM | **LEGACY**：仅承接历史分录，禁止新事件使用 |

- `type` 枚举沿用 ASSET/LIABILITY/REVENUE/EXPENSE/EQUITY；`normal_balance` 由 type 派生（ASSET/EXPENSE=DEBIT，其余=CREDIT），落列便于规则推导。
- `status`：`ACTIVE` / `LEGACY`。LEGACY 科目被新事件引用 ⇒ `LEDGER_ACCOUNT_LEGACY` 拒绝。

### 5.2 Account Instance（账户实例——真正参与记账的账户）

`accounts` 表就地演进为**实例表**（历史 `account_id` 引用不悬空），新列 `definition_code, owner_type, owner_id, currency`，唯一键 `uk(definition_code, owner_type, owner_id, currency)`：

```text
CHANNEL_RECEIVABLE ── ALIPAY / WECHAT / DOUYIN / MOCK   （owner_type=CHANNEL）
MERCHANT_PAYABLE   ── M001 / M002 / …                    （owner_type=MERCHANT）
FEE_REVENUE / BANK_CASH / SETTLEMENT_PAYABLE / SUSPENSE ── PLATFORM 单例
```

- 解析示例：`CHANNEL_RECEIVABLE + ALIPAY → 「支付宝渠道应收账户」`；`MERCHANT_PAYABLE + M001 → 「M001 商户应付账户」`。
- **Definition ≠ Account**：definition 是类型目录；可记账的是 instance。

### 5.3 旧模型迁移（不留两套）

| 旧（枚举 id） | 新 instance | 新事件默认 |
|---|---|---|
| 1 CUSTOMER_CASH | (CUSTOMER_CASH, PLATFORM, PLATFORM, CNY) | ❌ 禁止，改用 CHANNEL_RECEIVABLE/BANK_CASH |
| 2 MERCHANT_PAYABLE | (MERCHANT_PAYABLE, PLATFORM, **LEGACY**, CNY) 承接历史 | 新流水走 (MERCHANT, {merchantId}) |
| 3 PLATFORM_FEE_REVENUE | (FEE_REVENUE, PLATFORM, PLATFORM, CNY)——**id=3 保留、code 就地更名**，历史分录按 id 引用不受影响 | ✅ |
| 4 SETTLEMENT_PAYABLE | 同义迁移 | ✅ |
| 5 SUSPENSE | 同义迁移 | ✅ |

- **历史余额不拆户回填**（口径同 ADR-0065「存量数据不处理」先例）：LEGACY 商户应付实例承载切换前全部余额，切换起新流水入商户实例；商户当前应付 = LEGACY + 自身实例之和（查询侧合并，接口注明）。如需逐户还原，事后一次性脚本按 settlement 批次回填，**非 031 承诺**。
- `LedgerEntry.Type`（PAYMENT_CAPTURE/FEE/REFUND/SETTLEMENT/ADJUSTMENT）**废弃**：事件语义由 `ledger_transactions.event_type` 承载，分录行不再自带类型（`entry_type` 列停写，见 §14）。
- `LedgerSourceType` 收敛为**来源域**：`PAYMENT / REFUND / SETTLEMENT / RECONCILIATION`（原 `ADJUSTMENT` 迁到 `event_type=ADJUSTMENT + source_type=RECONCILIATION`；历史行不迁移）。

### 5.4 解析与开户策略

| definition | 解析失败时 |
|---|---|
| `MERCHANT_PAYABLE` | **自动开立**商户实例（商户存在由上游事实保证；开户是幂等的 insert-if-absent） |
| `CHANNEL_RECEIVABLE` / `CHANNEL_FEE_EXPENSE` | 渠道码不在注册表 ⇒ **fail fast 拒绝**（`LEDGER_CHANNEL_UNKNOWN`），配错不静默走默认（stage-design P5 纪律） |
| 平台级科目 | seed 预置，缺行即启动失败（seed 完整性自检） |

---

## 6. Accounting Event Model

### 6.1 事件类型

| eventType | 产生方（source_type） | 事实锚点（source_id） | 状态 |
|---|---|---|---|
| `PAYMENT_CAPTURE` | PAYMENT | `paymentNo` | 031 交付并切换 |
| `REFUND` | REFUND | `refundNo` | 031 交付并切换 |
| `MERCHANT_SETTLEMENT` | SETTLEMENT | `batchNo`（M1 收编，弃数值 batchId） | 031 交付并切换 |
| `ADJUSTMENT` | RECONCILIATION | `adjustNo`（AD 单号） | 031 交付并切换（017 五类调账 + 挂账的事件化表达） |
| `CHANNEL_FEE` | PAYMENT / RECONCILIATION | 费单/来源单号 | 031 交付契约与规则；产生方后置 |
| `CHANNEL_SETTLEMENT` | RECONCILIATION（真实渠道账单驱动） | 清算单号 | 031 交付契约与规则；**无产生方，规则空转 + 测试先行**（产生链路属 032+） |

### 6.2 入站契约（新 `AccountingEventRequest`，替换 `PostingRequest`——全内部契约，一刀切换，不留兼容层）

```java
record AccountingEventRequest(
    @NotBlank String eventType,        // §6.1 六类
    @NotBlank String sourceType,       // 来源域 PAYMENT/REFUND/SETTLEMENT/RECONCILIATION
    @NotBlank String sourceId,         // 业务单号（ADR-0063），禁数值 ID
    @NotBlank String currency,         // MVP 仅 CNY
    Long   grossAmountMinor,           // PAYMENT_CAPTURE / REFUND / CHANNEL_SETTLEMENT 用
    Long   merchantFeeMinor,           // 已算好的商户费，可为 0 / null=0
    Long   channelFeeMinor,            // 已算好的渠道费成本，可为 0 / null=0
    String merchantId,                 // PAYMENT_CAPTURE / REFUND / MERCHANT_SETTLEMENT 必填
    String channelCode,                // PAYMENT_CAPTURE / CHANNEL_* 必填（ALIPAY/WECHAT/…）
    Long   netAmountMinor,             // MERCHANT_SETTLEMENT 批次净额（带语义：可负）
                                       // CHANNEL_SETTLEMENT：渠道实付净额
    String adjustmentKind,             // ADJUSTMENT：SUPPLEMENT/REVERSE/CORRECT/TRANSFER/WRITE_OFF
    String fromAccountCode,            // ADJUSTMENT：转账语义「从 A 移到 B」
    String toAccountCode,              //   （非借贷指令；方向由规则按正常余额推导，§7.6）
    Long   amountMinor                 // ADJUSTMENT / CHANNEL_FEE 金额
) {}
```

- **幂等键不在契约里**：由 Ledger 派生 `{eventType}:{sourceId}`（原则 10）。
- 校验在 ledger api 层：按 eventType 的必填槽位表校验，缺槽 `EVENT_FIELD_MISSING` 拒绝（fail fast，不猜默认）。
- `Merchant Fee/Channel Fee` 字段语义即「上游已确认的费**事实**」——Ledger 见到的是数字，不是费率。

### 6.3 基数关系（显式钉死）

- **Accounting Event ≠ LedgerTransaction ≠ LedgerEntry**：事件 1:1 交易（LedgerTransaction，即现 `Posting` 演进）、1:N 分录。
- **Payment ≠ LedgerTransaction（禁 1:1 假设）**：一笔 `P001` 可关联 `PAYMENT_CAPTURE:P001` + 未来 `CHANNEL_FEE:P001` + 若干 `ADJUSTMENT:AD…`；其退款在 `R001` 名下。跨交易追溯沿用 `GET /entries?sourceType=&sourceId=` 扩展为按 `sourceId` 聚合查全部事件。

---

## 7. Posting Rules（正式规则表——规则的规范即本表，Strategy 代码一一对应）

### 7.1 PAYMENT_CAPTURE（merchantId=M, channelCode=C, gross, mFee, cFee）

| # | Debit | Credit | 条件 | 说明 |
|---|---|---|---|---|
| 1 | `CHANNEL_RECEIVABLE:C` gross | `MERCHANT_PAYABLE:M` gross | 恒有 | 钱在渠道 ⇒ 对渠道应收；钱属商户 ⇒ 对商户应付（**毛额**，不轧差） |
| 2 | `MERCHANT_PAYABLE:M` mFee | `FEE_REVENUE` mFee | mFee>0 | 商户费：**从欠商户的钱里扣**，成为平台收入 |
| 3 | `CHANNEL_FEE_EXPENSE:C` cFee | `CHANNEL_RECEIVABLE:C` cFee | cFee>0 | 渠道费：平台成本，**抵减对渠道的应收**（清算时渠道实付 gross−cFee） |

案例六代入即得 §2 终态余额表。恒等式：`ΣDr = gross+mFee+cFee = ΣCr` ✓。

### 7.2 REFUND（merchantId=M, channelCode=C, refundGross）

| # | Debit | Credit | 条件 | 说明 |
|---|---|---|---|---|
| 1 | `MERCHANT_PAYABLE:M` refundGross | `CHANNEL_RECEIVABLE:C` refundGross | 恒有 | 退款由渠道清算额中扣还 ⇒ 应收应付同时冲减（现为贷 CUSTOMER_CASH，迁移后不再触碰平台现金科目） |
| 2 | `FEE_REVENUE` feeRefund | `MERCHANT_PAYABLE:M` feeRefund | feeRefund>0 | 商户费退还：**仅当上游确认退**（契约槽位，默认 0） |
| 3 | `CHANNEL_RECEIVABLE:C` costRefund | `CHANNEL_FEE_EXPENSE:C` costRefund | costRefund>0 | 渠道费退回：同上，Ledger 不猜 |

### 7.3 CHANNEL_FEE（独立事件：渠道费在支付之后才确认时）

| # | Debit | Credit | 说明 |
|---|---|---|---|
| 1 | `CHANNEL_FEE_EXPENSE:C` fee | `CHANNEL_RECEIVABLE:C` fee | 与 `PAYMENT_CAPTURE.channelFeeMinor` **互斥口径**：同一笔费用的成本确认只允许走一条路径；MVP 约定随支付事实内联（规则 7.1#3），独立事件供渠道账单后确认场景（032+ 产生）。纪律：事件产生方保证同一来源单号不同时走两条路径（Ledger 键不同无法兜底，登记于 §18 风险）。 |

### 7.4 CHANNEL_SETTLEMENT（channelCode=C, netReceived=渠道实付净额）

| # | Debit | Credit | 说明 |
|---|---|---|---|
| 1 | `BANK_CASH` netReceived | `CHANNEL_RECEIVABLE:C` netReceived | 钱从渠道进平台银行户、应收核销。031 交付契约+规则+测试（桩产生方），真实账单驱动留 032+。 |

### 7.5 MERCHANT_SETTLEMENT（merchantId=M, net，源自现 SETTLEMENT 模板 + H3 负净额）

| # | Debit | Credit | 条件 | 说明 |
|---|---|---|---|---|
| 1 | `MERCHANT_PAYABLE:M` net | `SETTLEMENT_PAYABLE:M` net | net>0 | 应付转已结算（结转），语义同 ADR-0023 |
| 1' | `SETTLEMENT_PAYABLE:M` \|net\| | `MERCHANT_PAYABLE:M` \|net\| | net<0 | **H3**：负净额批次反向分录（商户倒欠，下期净额天然抵减）；替代现状「net≤0 不记账」造成的科目残留（C-07） |
| — | （不记账） | | net=0 | 空批次 |

真实出款（`Dr SETTLEMENT_PAYABLE / Cr BANK_CASH`）为未来 `MERCHANT_PAYOUT` 事件，**031 不做仅登记**。

### 7.6 ADJUSTMENT（017 五类调账与挂账的事件化）

转账语义：`amount X from A to B`。方向由规则按正常余额推导，**推导表**：

| from/to 类型 | 分录 |
|---|---|
| to 借余科目（ASSET/EXPENSE） | `Dr to` X |
| to 贷余科目（LIABILITY/REVENUE） | `Cr to` X |
| from 借余科目 | `Dr from` 取反 ⇒ 实际记 `Cr from` X |
| from 贷余科目 | 实际记 `Dr from` X |

例：挂账「账少记」（ADR-0065 §5）= `ADJUSTMENT from=OTHER_CREDIT to=…` 语义等价 `transfer X PLATFORM_CASH→SUSPENSE` 的现行模板由规则还原为 `Dr CUSTOMER_CASH/BANK_CASH、Cr SUSPENSE`——**audit 域只声明「挂多少、从哪到哪、哪一类」，不再传方向**。SUSPENSE 勾稽恒等式（余额 ≡ Σ未收口差异）保持并由试算平衡增强校验。

### 7.7 规则注册自检（启动期，防空转）

- 每个 eventType 恰一条规则；`PostingRuleRegistry` 缺规则 ⇒ 启动失败。
- 规则输出的全部 definitionCode 可解析；不可解析 ⇒ 启动失败。
- ArchUnit 补一条：`com.payment.ledger` 之外 **MUST NOT** 出现科目 code 常量（`"MERCHANT_PAYABLE"` 等字符串字面量仅允许出现在 ledger 与事件契约枚举中）——把「决策权收口」变成门禁而非评审约定。

---

## 8. Fee 边界（原则 4 的落地细则）

- Ledger **永不**：查商户费率 / 查渠道费率 / 调 Pricing Service / 算 `× 0.8%`。
- Ledger 接收的是 Financial Fact：`gross=10000, merchantFee=80, channelFee=60, currency=CNY, merchantId=M001, channel=ALIPAY`（minor 单位）。
- 费事实的**产生**（签约费率建模、支付时点算费）属上游后续 Feature（merchant 签约 + payment 计费），**不在 031 范围**；031 交付的是「费用事实一到，账立刻对」：契约字段 + 规则展开 + 以 `merchantFee=80 / channelFee=60` 测试数据端到端钉死 §2 案例（现状 F=0 盲区就此关闭）。
- 过渡期上游继续传 0：行为与现状一致，无资损。

---

## 9. LedgerTransaction / LedgerEntry（结构审计结论，ADR-0077）

**LedgerTransaction**（`postings` 表演进，聚合根 `Posting` 保留）：

`transaction_no`(现 `posting_no`，LP+雪花，ADR-0062 不变) · `event_type`(新) · `source_type`(域) · `source_id`(业务单号) · `currency` · `period`(新，G2) · `status`(PENDING/POSTED，见下) · `idempotency_key`(派生) · `created_at` · `posted_at`(新)。

**LedgerEntry**（不可变）：`id · posting_id · account_id(→instance) · direction · amount_minor · currency · created_at`。

- **不变量**：同币种 `ΣDEBIT == ΣCREDIT`（构造期门禁，保留）；`amount_minor > 0`（保留）；已 POSTED 禁任何 UPDATE/DELETE（保留，全仓无违反已核实）。纠正的唯一路径是**新的 ADJUSTMENT 事件**。
- 分录上的 `entry_type / source_type / source_id` 冗余列**停写停读**（追溯改走 `posting_id` join；`idx_entries_source` 由 `postings` 侧索引承接）。
- `status` 语义补齐：PENDING **仅在**待记账补偿重放等扩展场景启用（G3 场景）；正常路径恒 POSTED，不再留「定义但从未使用」的悬空态。

---

## 10. Balance 模型（G1 裁决：方案② 投影，ADR-0079）

- `ledger_entries` 恒为 Source of Truth；`account_balances` 为 **Projection / Read Model**：`(account_instance_id, currency, debit_total, credit_total, entry_count, last_entry_id, updated_at)`，`balance` 按科目 `normal_balance` 符号对外展示。
- **Posting atomicity**：交易、分录、投影在**同一本地事务**写入（同库同服务，无分布式问题）——投影与分录永不漂移的构造性保证。
- **一致性三层防护**：① 写入即平衡（构造期）；② 试算平衡 `Σ借 = Σ贷`（各币种独立，不平即 `BALANCE_BREAK` 告警 + 结算门禁硬拦，沿用 ADR-0065 分级）；③ 定期对账「投影 = Σentries」抽检 + **rebuild**：管理端点全量按分录重算（离线/低频，非在线路径）。
- **并发**：投影更新采用 `UPDATE ... SET debit_total = debit_total + ?` 原子累加（行锁天然按账户实例分片）；同事务内多行涉及同账户先聚合再单条累加，避免间隙死锁。真库并发验证依赖 033 Testcontainers（031 acceptance 显式登记该已知限制，沿用现状缺口口径）。
- **端点**（补齐 design-review Q7 缺口）：`GET /accounts/{id}/balance`、`GET /balances?currency=`（科目×商户/渠道维度列表）、`GET /trial-balance`（G1 即出）、`GET /trial-balance?period=`（G2）。

---

## 11. 期间与关账（G2，ADR-0079）

- `period CHAR(7)`（`YYYY-MM`）在落库时按 `posted_at` 派生，NOT NULL；历史行由迁移脚本按 `created_at` 回填。
- 新表 `ledger_periods(period, currency, status OPEN/CLOSED, closed_at, closed_by)`；缺行 = OPEN（保守）。
- 关账：`POST /periods/{period}/close` 前置校验 = 该期间试算平衡 且 无 `PENDING` 待记账残留 ⇒ 置 CLOSED。
- CLOSED 期间拒收新事件（含 ADJUSTMENT，错误码 `PERIOD_CLOSED`）；更正一律下期反向分录（与原则 8 一致）。期间维度与结算周期 `YYYY-MM` 口径对齐（032 的事实期间过滤在此地基上做）。
- **期间试算平衡表**：期初余额 + 本期发生 + 期末余额，跨期勾稽（账表核对的账务侧数据源）。

---

## 12. 待记账清单（G3，C-09/H2；吸收 B4 模式）

- 现状「失败只记指标」升级为**有载体**：新增表 `pending_postings(id, event_type, source_type, source_id, idempotency_key, payload_json, fail_reason, retry_count, status PENDING/REPOSTED/ABANDONED, created_at, updated_at, UNIQUE uk(event_type, source_id))`。
- 写入方 = **调用方**（记账 RPC 失败时落本地台账；Ledger 不可用时写 Ledger 自己没意义——落库在调用方侧的 ledger 出站组件，表放各调用方自己的 schema 还是 ledger schema？**裁决：调用方侧轻量台账**，ledger 提供 `GET /postings?eventType=&sourceId=` 回查供补偿核对；stage-design G3 原文的「业务侧写入」与此一致）。
- 补偿：`LedgerPostingGateway` 增加退避重试（有限次，幂等安全——Ledger 键派生保证重放不双记）+ 耗尽后留在台账待人工；reconciliation `MISSING_POSTING`（BLOCKER）审计发现机制不变，成为最后防线。
- **红线**：清单是补偿辅助，**不是**资金事实源；账务事实恒以 `ledger_transactions` 为准。

---

## 13. Payment → Ledger（及其余三域）调用链（目标态）

```text
PaymentResultProcessor / PaymentApplicationService（applyAndPersist 事务外，同步，ADR-0009 不变）
  → 组装 PAYMENT_CAPTURE 事件{paymentNo, merchantId(payments 新列), channelCode(成功 attempt),
      gross, merchantFee(暂 0), channelFee(暂 0), currency}
  → FeignLedgerPostingGateway（不再持有科目常量、不再算 netMinor）
  → POST /internal/ledger/accounting-events → PostingEngine → 1 DB 事务{tx + entries + balances}
  失败：落 pending 台账 + ledger.posting_failed 指标 + 对账 MISSING_POSTING 兜底
```

| 调用方 | 切换 | 上游必要适配 |
|---|---|---|
| payment（支付） | 事件化 | `payments` 加 `merchant_id`（下单请求由 order 携带——跨服务 DTO 加字段，非破坏性）；channelCode 取成功 attempt |
| refund | 事件化 | `RefundOrder` 反查所属 payment 的 merchantId/channelCode（已在退款域数据路径上） |
| settlement | 事件化 | sourceId 改 `batchNo`（M1）；net 传带符号净额，负值走 §7.5 1' |
| reconciliation-audit | 事件化 | 挂账/调账改发 ADJUSTMENT（转账语义 §7.6），删本地方向拼装 |

- 三条链路**维持同步**（ADR-0074 裁决不变）；「只对已确认事实记账」不变。
- `confirmed-facts` 只读 RPC 补 `merchantId`（H11 前置部分随本 Feature 落）。

---

## 14. 数据库模型（`ledger` schema 变更集，随 H6/H12 裁决）

```sql
-- ① 科目定义（新表，seed 受版本控制）
CREATE TABLE account_definitions (
    id BIGINT PRIMARY KEY, code VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL, normal_balance VARCHAR(8) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE', created_at DATETIME NOT NULL,
    UNIQUE KEY uk_def_code (code));

-- ② accounts 就地演进为实例表（历史 id 引用不悬空）
ALTER TABLE accounts ADD definition_code VARCHAR(32) NOT NULL,
    ADD owner_type VARCHAR(16) NOT NULL, ADD owner_id VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_instance (definition_code, owner_type, owner_id, currency);
-- 迁移：5 行存量补 (definition_code=旧 code（3 改 FEE_REVENUE）, PLATFORM, 2 号行 LEGACY 哨兵, CNY)

-- ③ postings 演进（LedgerTransaction）
ALTER TABLE postings ADD event_type VARCHAR(32) NOT NULL,
    ADD period CHAR(7) NOT NULL, ADD posted_at DATETIME NOT NULL,
    ADD UNIQUE KEY uk_event_source (event_type, source_id),   -- 事件级幂等（新）
    ADD KEY idx_postings_period (period, status);
-- idempotency_key 语义改「Ledger 派生 {eventType}:{sourceId}」，uk 保留（双约束）

-- ④ ledger_entries 收敛：entry_type/source_type/source_id 停写；ledger_entries 无结构删列
--   （MySQL 8 ADD COLUMN IF NOT EXISTS 方言问题属 033 T2，本轮按 information_schema 守卫模式写迁移）

-- ⑤ 余额投影（新表）
CREATE TABLE account_balances (
    account_instance_id BIGINT NOT NULL, currency VARCHAR(8) NOT NULL,
    debit_total BIGINT NOT NULL DEFAULT 0, credit_total BIGINT NOT NULL DEFAULT 0,
    entry_count BIGINT NOT NULL DEFAULT 0, last_entry_id BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL, PRIMARY KEY (account_instance_id, currency));

-- ⑥ 期间（新表） ⑦ 调用方侧 pending 台账（新表，各调用方 schema）
-- ⑧ 顺带收口 code-debt #1（🔴 High）：01-create-databases.sql 补 ledger 库
```

迁移脚本全部走**空库/存量库双可重放**模式（engineering-standards；033 T2 门禁未立前按现有 018 守卫先例执行）。

---

## 15. User Scenarios & Acceptance

### US1 — 事件驱动记账与边界收口（P1，031-A）

支付/退款/结算/审计四域以事件记账；任何上游代码不再出现科目与方向。

1. **Given** 支付成功事实（gross=100.00, mFee=0.80, cFee=0.60, M001, ALIPAY），**When** 发 `PAYMENT_CAPTURE`，**Then** 按 §7.1 生成 5 条分录，四账户余额精确等于案例六终态，`Σ借=Σ贷`。
2. **Given** 同一事件重放（并发双发/重试），**When** Ledger 收到，**Then** 恰一条交易返回首次结果，无重复分录（撞 `uk_event_source` 回放）。
3. **Given** 上游任一调用方源码，**When** ArchUnit 扫描，**Then** 无科目 code 常量、无 direction 字面量（§7.7 门禁）。
4. **Given** `feeMinor>0`，**When** 全链路（含 FEE 分支），**Then** 端到端测试通过——关闭 F=0 历史盲区。

### US2 — 分户余额视图（P1，031-A/B）

1. 支付→退款→结算串联后：`GET /balances` 可按商户实例、渠道实例逐户展示；`GET /trial-balance` 借贷恒等。
2. 商户 M001 应付 = LEGACY 历史实例 + M001 实例合并口径，接口文档明示。

### US3 — 期间与关账（P2，031-B）

1. 落库事件自动带 `period`；关账后该期间任何事件（含 ADJUSTMENT）⇒ `PERIOD_CLOSED` 拒绝。
2. 期间试算平衡表期初+发生=期末，跨期勾稽平。

### US4 — 待记账清单（P2，031-B）

1. 记账 RPC 注入失败 ⇒ 台账有记录、退避重试后收敛为 REPOSTED、全程无双记。
2. 负净额结算批次 ⇒ §7.5 1' 反向分录，`MERCHANT_PAYABLE` 无残留、`CROSS_LEDGER_MISMATCH` 可对平（C-07 关闭）。

### Edge Cases

- 未知渠道码 ⇒ fail fast 拒绝且可观测；未知商户 ⇒ 自动开户幂等。
- LEGACY 科目被新事件引用 ⇒ 拒绝。
- 同 event_type 不同 sourceId / 同 sourceId 不同 event_type ⇒ 都是合法新交易（幂等键为**组合**）。
- 关账与补偿重试竞态 ⇒ 重试撞 `PERIOD_CLOSED` ⇒ 留台账人工（不自动跨期改记）。
- 投影 rebuild 期间读请求 ⇒ 读旧投影不阻塞；rebuild 完成后原子切换校验和（管理操作，低频）。

---

## 16. Non-Goals（明确不做，防 scope 膨胀）

❌ Rule DSL / 规则配置表 / 可视化规则平台（代码 Strategy 即上限）　❌ 费率计算与签约费率模型（上游后续 Feature）　❌ 科目动态管理界面（seed + ADR 纪律）　❌ 多币种折算 / FX / 汇兑损益（G4 触发条件不变）　❌ 税务、ERP、多法人、会计准则全套（科目表即最小够用）　❌ 真实出款 / 银行对接（MERCHANT_PAYOUT 仅登记）　❌ CHANNEL_SETTLEMENT 真实产生链路（032+）　❌ 记账链路异步化（ADR-0074）　❌ 历史余额逐户回填　❌ CQRS/Event Sourcing　❌ Testcontainers 本身（033 交付，031 登记依赖）

---

## 17. 人类决策边界（MUST 负责人裁决后生效）

| # | 事项 | 边界类型 | 载体 |
|---|---|---|---|
| D-1 | `PostingRequest`→`AccountingEventRequest` 契约一刀切换（内部 RPC 破坏性变更） | 公共 API 变更（内部） | ADR-0077 |
| D-2 | 科目表变更：新增 3 科目、`PLATFORM_FEE_REVENUE→FEE_REVENUE`、CUSTOMER_CASH 判 LEGACY | 领域模型/账务语义（G4） | ADR-0078 |
| D-3 | `account_definitions`/`account_instances`(accounts 演进)/`account_balances`/`ledger_periods`/调用方 pending 台账 5 处表变更 + `postings` 加列 | Database Schema（H6/H12 正式落地） | ADR-0079 + 本 Spec §14 |
| D-4 | `payments` 加 `merchant_id` + order→payment 请求 DTO 加字段（H11 前置） | Schema + 跨服务 DTO | 本 Spec §13 |
| D-5 | 负净额结算改「反向分录」而非「不记账」 | 资金路径行为变更（H3/C-07） | ADR-0077 §决策 |
| D-6 | 旧键 `PAYMENT:payment:*` 历史 posting 保留不迁移；新键切换 | 数据口径 | ADR-0077 |
| D-7 | 历史余额不逐户回填的口径 | 数据口径 | 本 Spec §5.3 |

## 18. 风险与依赖

| 风险/依赖 | 处置 |
|---|---|
| 切换窗口内新旧契约混用 | 031-A 一个 PR 批次内完成契约 + 四调用方 + 测试，不跨批留双轨 |
| `CHANNEL_FEE` 双路径双计（§7.3） | 产生方纪律 + 032 设计时复核；Ledger 侧无法以键兜底（键不同），显式登记 |
| 真库并发（投影/唯一键）无自动化 | 依赖 033（Testcontainers）；acceptance 显式记录未覆盖范围，不得默认通过 |
| 032（对账）依赖本 Feature 的余额/期间视图与 merchantId | 顺序：031 先于 032（design-review 门 3 已定） |
| 审计四核对读 posting 结构（`LedgerPostingView` 等） | 031-A 同步适配 audit 侧只读模型，回归 017 全部测试 |

## 19. 交付切分

- **031-A**（边界地基）：事件契约 + PostingEngine/Rule/Resolver + 两级科目 + 双唯一约束 + 四调用方切换 + `payments.merchant_id` + ArchUnit 门禁 + US1/US2 核心断言。**Schema 变更集中于此批。**
- **031-B**（账的纵深）：`account_balances` 投影 + 余额/试算平衡端点 + 期间/关账 + 待记账台账与退避重试 + US2/US3/US4。
- 每批独立 feature 分支 + PR 合并（Constitution 提交纪律），不跨批堆积。

## 20. 文档同步清单（随实现/裁决发生，本 Spec 只登记）

`systems/ledger-service.md`、`systems/payment-service.md`（merchant_id）、`technical-solution.md` 账务节、`roadmap.md` Current Status、`docs/adr/README.md` 索引（已随本 Spec 预登记 Proposed）、ADR-0008 状态注记（已加）、stage-design §4/§9（已勘误）。
