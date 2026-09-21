# ADR-0077 ~ ADR-0079：Ledger / Accounting 地基（Feature 031）架构决策集合

> 承载 [spec 031-ledger-accounting-foundation](../specs/stage-05-channel-and-finance-deepening/031-ledger-accounting-foundation/spec.md) 的三条决策。
> 状态：**🟡 Proposed（2026-09-21 提出，待负责人裁决）**——涉及 Constitution §Governance
> 「人类决策边界」（账务语义、公共内部 API、资金表结构、资金路径行为变更），**未 Accepted 前不得实现**。
> 编号说明：本 Feature 编号 **031**（负责人 2026-09-21 裁决；stage-design 旧名 `032-ledger-account-view` 作废并勘误）。

---

<a id="adr-0077"></a>
## ADR-0077: 记账契约从「原始分录」改为「Accounting Event + Posting Rule」

- **状态**：🟡 Proposed
- **日期**：2026-09-21
- **关联 Feature**：`031-ledger-accounting-foundation`
- **关系**：**Partially Supersedes ADR-0008**（「分录由调用方组装并传入 accountId/direction」的契约部分）；**修订 ADR-0009 / ADR-0018 / ADR-0023 的契约描述**（触发时机、同步 RPC、失败不回滚、只记已确认事实——**语义全部不变**）。ADR-0008 的复式结构（Posting+LedgerEntry）、平衡门禁、append-only、幂等范式**保留**。

### Context

审计（2026-09-21）确认：`PostingRequest` 让 payment / refund / settlement / reconciliation-audit **四方**直接决定借哪个科目、贷哪个方向，科目 ID 常量在四个服务中复制；毛/净轧差（`netMinor = gross − fee`）发生在 payment 网关内。后果：① 账务语义散落非账务域，违反「核心领域边界」同级的职责纪律；② 未来真实费率接入时，费用分摊口径由支付域决定，属资金正确性风险敞口；③ 科目调整需改 4 个服务。

### Decision

1. **边界**：上游只发 **Accounting Event / Financial Fact**（`eventType + sourceType + sourceId + currency + 已算好的金额槽 + merchantId/channelCode`）；**借贷方向、科目、分录结构 exclusively 归 Ledger**。契约中不再出现 `accountId` / `direction`（ADJUSTMENT 传**转账语义** `from/to 科目 code`，方向由规则按正常余额推导）。
2. **事件类型**：`PAYMENT_CAPTURE / REFUND / CHANNEL_FEE / CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / ADJUSTMENT`；**Payment ≠ LedgerTransaction**（一笔支付可对应 capture + 渠道费 + N 个调整），**Event ≠ Transaction ≠ Entry**（1:1:N）。
3. **规则形态**：每 eventType 一条 **Java Strategy**（`PostingRule → PostingLine → AccountResolver → PostingEngine`）+ 注册表启动期自检 + ArchUnit「ledger 之外禁现科目常量」门禁。**不做** DSL / 规则表 / 热加载 / 可视化配置。
4. **幂等**：幂等键由 Ledger 派生 `{eventType}:{sourceId}`（上游不再拼键，根治双前缀类缺陷）；`uk_postings_idempotency_key` 保留 + 新增 `uk(event_type, source_id)` 双约束；先查后插 + 撞键回放不变。
5. **切换**：全内部契约，**一刀切换不留兼容层**；旧键历史 posting 保留不迁移（口径同 ADR-0065 存量先例）。
6. **吸收存量裁决项**：M1（settlement sourceId 数值 batchId → batchNo）；H3/C-07（负净额结算批次由「不记账」改为**反向分录**——资金路径行为变更，需随本 ADR 一并裁决）。

### 备选方案

- **A. 维持调用方传分录，仅在文档重申边界**：零成本，但把已证伪的约定（四方常量复制 + F=0 从未验证）固化为设计——否决。
- **B. 引入规则 DSL/引擎**：违背「不加为炫技的复杂度」（Constitution §I）——否决。
- **C. 代码 Strategy + 事件契约（采纳）**。

### Consequences

**正面**：账务语义单点收敛；费率上线零改上游契约；幂等由 DB 结构兜底；032 的商户/期间维度有账务地基。
**代价**：一次跨 4 个调用方的契约切换（单批 PR 收口）；ledger 代码量增加（规则族 + 引擎）。

---

<a id="adr-0078"></a>
## ADR-0078: 科目模型升级为 Definition / Instance 两级，扩展渠道资金科目

- **状态**：🟡 Proposed（**Constitution §Governance：科目表变更 = 人类决策边界**）
- **关联**：扩展 ADR-0008「预置科目表」；**修订 ADR-0011 边界**（当年排除的「渠道清算科目」现予引入，清算**链路**仍不做）；G4「新增科目 MUST 走 ADR」纪律不变，本 ADR 即该次履行。

### Context

业务模型锁定为**平台代商户收款**：用户付 100 元，平台不天然拥有——形成对渠道的应收 + 对商户的应付。现状 5 科目枚举把这两件事压成一条 `CUSTOMER_CASH`（支付即视为已到平台钱），且 `MERCHANT_PAYABLE` 全局单条无法回答「欠 M001 多少」——这正是 C-20/N1 跨商户串账风险的账务侧根因，也让试算平衡、结算报表、032 的期间过滤都无从分户。

### Decision

1. **两级模型**：**Account Definition**（类型目录：code/type/正常余额方向/status，表化 + seed 受版本控制）≠ **Account Instance**（可记账账户：`definition + owner_type/owner_id/channel + currency`，唯一键约束，只增不改不删）。
2. **科目表（7 + 1 LEGACY）**：新增 `CHANNEL_RECEIVABLE`（按渠道分户）、`BANK_CASH`、`CHANNEL_FEE_EXPENSE`（按渠道分户）；保留 `MERCHANT_PAYABLE`（**按商户分户**）、`SETTLEMENT_PAYABLE`、`SUSPENSE`（017 语义不变）；`PLATFORM_FEE_REVENUE` 更名 `FEE_REVENUE`（id=3 保留、code 就地改，历史分录按 id 引用不受影响）；`CUSTOMER_CASH` 判 **LEGACY**（只承接历史，新事件引用即拒）。
3. **开户策略**：`MERCHANT_PAYABLE` 商户实例**自动开立**（幂等 insert-if-absent）；渠道科目未知渠道码 **fail fast**；平台级科目 seed 预置、缺行启动失败。
4. **历史口径**：旧 5 行原位保留为实例（2 号 MERCHANT_PAYABLE 挂 `LEGACY` 哨兵 owner 承载切换前全部历史余额）；**不做逐户回填**，商户当前应付 = LEGACY + 自身实例（查询侧合并）；逐户还原留一次性脚本选项，非承诺。
5. `accounts` 表**就地演进**为实例表，历史 `account_id` 引用不悬空——迁移式改造，不留两套科目模型并存。

### 备选方案

- **A. 只加科目不加实例维度**：改动最小，但分户诉求（欠谁多少、哪家渠道应收）仍要靠新事件再翻倍——否决。
- **B. 全新表 + 历史数据迁移重指向**：一致性最干净，但要 UPDATE 历史分录的 account_id，违反 append-only 精神且回滚昂贵——否决。
- **C. 就地演进 + LEGACY 哨兵（采纳）**。

### Consequences

**正面**：能按渠道/商户出账；032 商户维度与结算报表有账务支撑；渠道资金流转（应收→银行现金）首次可表达。
**代价**：科目语义从代码枚举转数据表（变更纪律靠 seed + ADR）；商户应付历史/新期两实例并存需在查询口径长期标注。

---

<a id="adr-0079"></a>
## ADR-0079: 余额为分录投影（同事务更新）+ 期间/关账 + 待记账台账

- **状态**：🟡 Proposed（新增关键资金表 = 人类决策边界，正式落地 stage-design H6/H12 的裁决请求）
- **关联**：兑现 stage-design §4.2 G1/G2/G3；投影不变量并入 ADR-0065 结算门禁（试算不平 ⇒ 硬拦）。

### Decision

1. **G1 取方案②**：新增 `account_balances` 投影表，与 `postings + ledger_entries` **同一本地事务**原子累加更新（同库同服务，无分布式问题）；`ledger_entries` 恒为 Source of Truth，投影可校验（试算平衡 + 定期对账「投影 = Σentries」）可 rebuild（管理端全量重算，低频离线）。
2. **G2**：`postings` 加 `period`（YYYY-MM，按 postedAt 派生 NOT NULL）与 `posted_at`；`ledger_periods` 关账表；**CLOSED 期间拒收一切新事件（含 ADJUSTMENT）**，更正走下期反向分录；交付科目余额、全科目试算平衡、期间试算表（期初+发生=期末勾稽）四类端点。
3. **G3**：记账失败落**调用方侧** `pending_postings` 台账（事件载荷快照 + 退避重试 + 人工兜底），Ledger 提供按 `(eventType, sourceId)` 回查供补偿核对；红线：台账非事实源，账务事实恒以 postings 为准；reconciliation `MISSING_POSTING` 审计仍为最后防线。
4. 已知限制显式登记：投影并发与唯一键的真库验证依赖 033（Testcontainers），031 acceptance 不得默认通过该缺口。

### Consequences

**正面**：`/balance` 全表内存聚合的规模问题终结；账可按期切、可关账、可出审计报表（账表核对数据源）；「哪笔没记上」从靠告警翻日志变成可查询可补偿。
**代价**：新增 3 处表变更（H6/H12）；投影引入「余额=分录」恒等的新不变量需三层防护长期守护。

---

## 负责人裁决清单（对应 spec 031 §17）

D-1 契约一刀切换 / D-2 科目表变更 / D-3 五处表变更+postings 加列 / D-4 `payments.merchant_id`+order→payment DTO / D-5 负净额反向分录 / D-6 旧键不迁移 / D-7 历史余额不逐户回填。

**全部 Accepted 前**：031 不得进入 `/speckit-implement`；`stage-design.md` §4.2 保持【待确认】标注。
