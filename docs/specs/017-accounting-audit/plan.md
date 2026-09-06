# 方案：会计四核对（账证 / 账账 / 账实 / 账表）在 PaymentArch 的落地

**版本**：0.4（已实施：2026-09-07 代码合并 master，测试与 live 冒烟通过）
**日期**：2026-09-06
**状态**：✅ Accepted → **已实施**（2026-09-07 落地 63f73d1）
**关联**：spec 004-ledger（FR-007/FR-008、SC-005）、spec 006-reconciliation、spec 007-settlement、spec 016（N4 缺口）、spec 011-demo-showcase、ADR-0054、ADR-0065

> **v0.2 变更**（本轮需求："加测试和验收、要模拟数据、demo 里搞个界面能看到触发/执行/结果、挂账调账"）
> 1. 新增 **§7 挂账与调账**（Suspense / Adjustment）：差异不再只是"记录 + 人工备注"，而是有**会计处置动作**与**复核对闭环**；
> 2. 新增 **§8 测试策略 + 模拟数据集**（F1~F9 fixture，逐条给出注入方式与期望差异类型）；
> 3. 新增 **§9 验收标准与用例矩阵**（TC-01~TC-20 + 演示脚本 `scenario-audit.sh` 断言清单）；
> 4. 新增 **§10 Demo 对账控制台**：`mock-channel-web` 静态页 `audit.html`（8091），可看到任务触发 → 执行日志 → 差异结果 → 挂账 → 调账 → 复核 → 关闭全链路；原型以**文档附件**形式交付（`audit-console-mockup.html`，离线可点），真实 API 待后端落地后按同契约接入（任务 T070 迁入工程目录）；
> 5. §11 待拍板决策点新增两条（挂账科目、双人复核）。
>
> **文档分工（避免重复维护）**：`spec.md` 是 SC 与 FR 的**唯一事实源**；本文档给技术方案与测试映射；`tasks.md` 给任务分解；`acceptance.md` 给验收执行与 DoD。

---

## 1. 先说结论

你说的四核对是：**账证核对、账账核对、账实核对、账表核对**（会计基础工作「对账」的四个维度）。

映射到本系统后，**结论是一半有、一半缺，且缺的那一半里有一条是「文档已经声明要做、但代码从没做」**：

| 核对 | 在本系统的含义 | 现状 | 结论 |
|---|---|---|---|
| **账证** | 账本分录 ↔ 业务单据（支付单 / 退款单 / 结算批次） | 只有**单向追溯**能力，**无覆盖率校验** | ❌ **缺口，且 SC-005 已声明要做** |
| **账账** | 账本内部借贷平衡 + 科目勾稽 + 跨账（ledger ↔ settlement） | 借贷平衡 ✅ 已有；科目勾稽 / 跨账 ❌ | ⚠️ **半缺** |
| **账实** | 账 / 台账 ↔ 外部真实资金（渠道账单） | 只做「**业务台账** ↔ 渠道账单」，**完全不碰账本** | ⚠️ **做了一半，账本被跳过** |
| **账表** | 账 ↔ 对外输出（结算单 / 对账报表 / 商户账单） | 无 | ❌ 缺口（优先级最低） |

**最关键的一条**：`docs/specs/004-ledger/spec.md:117` 的 **SC-005** 白纸黑字写着——

> 账本对所有已确认资金事实的覆盖率 100%（无「业务已确认但账本无分录」的孤儿事实，**由 reconciliation 校验**）

但实测 `grep -rn "ledger" reconciliation-service/src/main/java` **零命中**——reconciliation-service 从头到尾没有引用过账本。也就是说：**这条验收标准声明了，却没有任何代码在校验它**。这是本方案要补的头号缺口。

**再补一句本轮新增的判断**：只"报出差异"是不够的。差异报出来之后如果只能写一句备注就关批，会计上等于**什么都没做**。所以本方案在 §7 补上**挂账（先安置）→ 调账（后定性）→ 复核（再验证）→ 关闭**的处置闭环，这一环也是 demo 界面上最值得演示的部分。

---

## 2. 现状盘点（带代码证据）

### 2.1 已有的能力

| 能力 | 位置 | 说明 |
|---|---|---|
| 复式记账 + 借贷平衡 | `ledger-service/.../domain/Posting.java`；`09-ledger-schema.sql` | FR-001/FR-002：每个 Posting 必须 `sum(debit)==sum(credit)`，不平衡直接拒绝不落分录 |
| 全局平衡性校验 | `ledger-service/.../application/BalanceChecker.java`（`isBalanced()` / `byCurrency()`） | FR-007：按币种 `sum(debit)-sum(credit)` 应为 0 |
| 科目余额计算 | `BalanceChecker.accountBalance(accountId, currency)` | 借方为正、贷方为负 |
| 按来源追溯 | `BalanceChecker.entriesOfSource(...)`；`LedgerController:76 GET /internal/ledger/entries` | FR-008：分录可经 `source_type + source_id` 追溯业务来源 |
| 平衡性端点 | `LedgerController:69 GET /internal/ledger/balance` | 运维/对账可调用 |
| 记账幂等 | `postings.uk_postings_idempotency_key` | FR-004：重复记账撞唯一约束回查首次结果 |
| 分录不可变 | `ledger_entries` append-only，更正只加反向分录 | FR-003 |
| 渠道对账（外部方向） | `reconciliation-service`：平台已确认事实 ↔ 渠道账单，按 `reference`（= `channel_reference`）匹配，4 类差异 + 批次状态机 | spec 006；差异未清零拒绝关账，关账是结算前置 |
| 结算记账 | `settlement-service/.../LedgerPostingGateway.java` | 结算批次经此单向记账给 ledger |

### 2.2 缺口

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| **G1** | **账证核对完全没有**（覆盖率无校验） | `reconciliation-service` 对 ledger 零引用；SC-005 无实现 | 业务已确认但账本漏记（记账 RPC 失败、重试耗尽）**无人发现**；反之账本孤儿分录也无人发现 |
| **G2** | 账账核对只有借贷平衡，无科目勾稽 | 只有 `BalanceChecker`，无「科目余额应等于业务口径推导值」的校验 | 分录方向/科目记错但借贷仍平衡 → 平衡性校验**查不出来** |
| **G3** | 跨账（ledger ↔ settlement）无核对 | settlement 只单向记账，不回查 | 结算批次金额与账上「应付→已结」分录可能不一致 |
| **G4** | 账实核对跳过账本 | reconciliation 的 PlatformFact 来自 payment/refund 事实，**不含 ledger** | 渠道账单与**资金科目发生额**之间无勾稽 |
| **G5** | 账表核对无 | settlement 产出 `settlement_items` / `settlement_adjustments`，无与账本回算一致性校验 | 结算单与账本可能不一致 |
| **G6（已修复 · 原 N4）** | 退款渠道流水号不落库（spec 016 / 8a986a8 已修复落库） | `RefundFactsService` 优先取 `payment_attempts`(`attempt_type=REFUND`) 的 `channel_reference` 真实号；**存量迁移数据沿用 `refund-{id}` 合成引用兜底并 WARN（负责人裁决：存量数据不迁移）** | 退款方向现已能按真实渠道退款流水号做账实比对 |
| **G7（本轮新增）** | **差异只有"记录"，没有会计处置** | 现有 `resolveDifference(...)` 只写 `resolution_note`，不产生任何分录 | 差异可以"嘴上处理完"，账上差额原地不动；**无挂账、无调账、无复核对**，闭环断裂 |

---

## 3. 目标：四核对各自的比对口径

设计原则（沿用既有对账纪律）：**只读、事后、不自动修钱**——核对只产出差异台账，**处置必须由人发起**（§7），处置本身走标准记账通道留痕；差异收口后才允许关账。

### A1 · 账证核对（优先补，兑现 SC-005）

- **输入**：① 业务侧已确认事实集（payment `SUCCEEDED`、refund 已确认、settlement 批次 `SUCCEEDED`）；② 账本 `postings` + `ledger_entries`。
- **连接键**：`(source_type, source_id)`——即 `PAYMENT/PM号`、`REFUND/RF号`、`SETTLEMENT/批次号`（ADR-0063 业务单号口径）。
- **双向比对**：
  - 业务有、账本无 → `MISSING_POSTING`（**漏记账**：记账 RPC 失败/重试耗尽的真实兜底缺口）
  - 账本有、业务无 → `ORPHAN_POSTING`（**孤儿分录**：多记/错记）
  - 两边都有但金额不符 → `AMOUNT_MISMATCH`
  - 币种/方向不符 → `CURRENCY_MISMATCH` / `DIRECTION_MISMATCH`
  - 同一 `(source_type, source_id)` 多条 posting → `DUPLICATE_POSTING`（幂等被击穿）
- **覆盖**：三个来源全覆盖（支付、退款、结算）。

### A2 · 账账核对

- **A2-1 借贷平衡**（复用现有 `BalanceChecker`）：按币种差额为 0。
- **A2-2 科目勾稽**（新增）：按科目推导应有余额并与账本实算比对，例：
  - `MERCHANT_PAYABLE`（应付商户净额）余额 ?= Σ已确认支付 - Σ已退款 - Σ已结算净额
  - `PLATFORM_FEE_REVENUE` ?= Σ手续费
  - `SETTLEMENT_PAYABLE` ?= 已结未出款
  - `SUSPENSE`（待处理差错款，§7 新增）余额 ?= Σ 未收口差异的挂账净额（**挂账台账与账本互证**）
  - 差额 → `ACCOUNT_RECON_BREAK`
- **A2-3 跨账**：settlement 批次 `settlement_items` 合计 ↔ 该批次在 ledger 的 posting 金额 → `CROSS_LEDGER_MISMATCH`。

### A3 · 账实核对（升级现有对账）

- **现状链路（保留）**：业务台账（payment/refund 已确认事实）↔ 渠道账单，按 `channel_reference`。
- **新增链路**：**账本资金科目发生额 ↔ 渠道账单**（同一周期、按渠道维度汇总/逐笔）。
- **两条链路并列产出差异**，任一有差异则该周期账实不符；退款真实流水号已由 spec 016 落库（N4 已修复），退款方向可正常按真实号比对；**存量数据沿用合成引用兜底（负责人裁决不迁移），不再构成前置阻塞**。
- 差异类型沿用现有 4 类（金额/状态/平台独有=漏单/渠道独有=长款），另加 `LEDGER_VS_STATEMENT_BREAK`。

### A4 · 账表核对（最后做）

- 结算报表 / 商户账单 / 对账报表的金额与明细，**回算**自 ledger + settlement 数据，比对报表输出值。
- 差异 → `REPORT_MISMATCH`。

---

## 4. 放哪个服务做？（架构决策点）

| 选项 | 评价 |
|---|---|
| **A. 扩展 `reconciliation-service`（推荐）** | 它已经是「只读、事后、控制面」的独立服务（8088），**已有批次状态机 + 差异模型 + 只读拉事实的 RPC 模式**，四核对正是同一类作业。不新增服务，不违反依赖方向（reconciliation 本就被允许读多服务） |
| B. 新建 `accounting-audit-service` | 职责最纯，但为一个控制面作业再开一个服务，运维/端口/库都要加，性价比低 |
| C. 放 `ledger-service` 内 | ❌ **不可行**：Constitution §III + FR-005 明确 ledger 只能被依赖、**MUST NOT 反向依赖任何业务领域**，而账证核对要去读 payment/refund/settlement |

**推荐 A**：在 `reconciliation-service` 内新增 `audit` 包，复用其批次/差异骨架。

**执行方式**：沿用项目既有 `@Scheduled` 先例（`OrderTimeoutScheduler` / `ChannelQueryScheduler` / `TimeoutScanScheduler`）——日切触发 + 手动端点触发，**不引入 MQ / 分布式事务**（Constitution §IV、ADR-0001）。

> **§7 的挂账/调账会"写账"**，这不违反上面的只读原则吗？不违反：**读与判定**仍是 reconciliation 只读完成，**写**只发生在两处——① 写自己的 `audit_*` 表；② 经 ledger 的**标准记账接口**（与 settlement 现有 `LedgerPostingGateway` 同款通道）新增 `ADJUSTMENT` 类型 posting。 reconciliation **仍然不写 payment / refund / settlement 的任何业务数据**。

---

## 5. 数据模型 delta（reconciliation 库）

新增/扩展三张表（不改动 ledger / payment / settlement 既有的业务表；ledger 侧只新增一个科目行 + 沿用既有记账通道）：

```text
audit_batches
  id, batch_no (AB+雪花), period, scope (CERTIFICATE|LEDGER|REAL|REPORT),
  status (PROCESSING | BALANCED | HAS_DIFFERENCE | RECHECKING | CLOSED),
  checked_count, difference_count, suspended_amount_minor, adjusted_amount_minor,
  triggered_by, started_at, finished_at, created_at, updated_by, version
  UNIQUE KEY uk_audit_batches_batch_no
  UNIQUE KEY uk_audit_batches_period_scope (period, scope)   -- 幂等：同 period+scope 只跑一次（重跑=recheck）
  KEY idx_audit_batches_period_scope (period, scope)

audit_differences
  id, batch_id, kind, severity (BLOCKER|MAJOR|MINOR),
  source_type, source_id, reference,
  expected_amount_minor, actual_amount_minor, currency,
  status (PENDING | SUSPENDED | ADJUSTED | VERIFIED | RESOLVED),
  suspended_amount_minor, adjusted_amount_minor,
  detail, resolution_note, resolved_by, resolved_at
  KEY idx_audit_diff_batch (batch_id), KEY idx_audit_diff_source (source_type, source_id)

audit_adjustments            -- v0.2 新增：挂账/调账台账
  id, adjust_no (AD+雪花), batch_id, difference_id,
  kind (SUSPEND | SUPPLEMENT | REVERSE | CORRECT | TRANSFER | WRITE_OFF),
  debit_account_code, credit_account_code,
  amount_minor, currency,
  posting_no,                          -- 记入 ledger 的批次号（ADJUSTMENT 来源）
  status (POSTED | REVERSED),
  operator, reviewer, reason, created_at
  UNIQUE KEY uk_adjustments_adjust_no
  KEY idx_adj_diff (difference_id)
```

**差异类型枚举**（`AuditDifferenceKind`）：
`MISSING_POSTING` / `ORPHAN_POSTING` / `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` / `DIRECTION_MISMATCH` / `DUPLICATE_POSTING` / `BALANCE_BREAK` / `ACCOUNT_RECON_BREAK` / `CROSS_LEDGER_MISMATCH` / `LEDGER_VS_STATEMENT_BREAK` / `REPORT_MISMATCH`

### 5.1 ledger 侧：`SUSPENSE` 科目变更（Constitution §8 变更，**2026-09-06 已批准**）

> 属「领域模型 / 数据库 Schema」变更，按 Constitution §8 走提案 + 人类确认；**负责人已裁决：可以增加**。此处写清变更全貌。

| 项 | 内容 |
|---|---|
| **科目定义** | `id=5`、`code=SUSPENSE`、`name=待处理差错款`、`type=ASSET`、`currency=CNY` |
| **为什么是 ASSET** | 挂账的钱是"已收到但归属待定"的款项，属平台代为持有的资产；期末余额代表"尚未定性的钱" |
| **代码改动** | `ledger-service/.../domain/Account.java` 枚举新增 `SUSPENSE(5L, "SUSPENSE", AccountType.ASSET)`（枚举新增不改既有 4 个科目的 id/code） |
| **Schema 改动** | 幂等 seed 追加到 `deployment/schema/09-ledger-schema.sql` 末尾（`ON DUPLICATE KEY UPDATE`，可重复执行；不 DROP、不 ALTER 既有表结构） |
| **影响面** | ① `BalanceChecker` 按 `account_id` 聚合，**自动纳入**，无需改代码；② A2 勾稽新增一条公式（`SUSPENSE` 余额 == Σ 未收口差异挂账净额）；③ 既有 4 科目、既有分录、payment/refund/settlement **零影响**；④ `accounts` 表当前无 `AUTO_INCREMENT`，新增固定 id 无冲突 |
| **回滚方式** | 科目行保留（无害）；如需下线，仅需停用 `SUSPEND` 入口并把余额 `TRANSFER` 转出，不改 Schema |

```sql
-- deployment/schema/09-ledger-schema.sql 末尾追加（幂等）
INSERT INTO accounts (id, code, name, type, currency, created_at) VALUES
    (5, 'SUSPENSE', '待处理差错款', 'ASSET', 'CNY', NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name);
```

**只读/可写边界（红线）**：写仅限 `audit_*` 表 + ledger 的 `ADJUSTMENT` posting；**绝不 UPDATE/DELETE 任何既有分录，绝不改 payment/refund/settlement 的业务状态**。

---

## 6. 分阶段落地

| 阶段 | 内容 | 依赖 | 价值 |
|---|---|---|---|
| **P0** | **复核确认 N4（已在 spec 016 修复，无开发量）**：退款渠道流水号已落 `payment_attempts`(`attempt_type=REFUND`，`channel_reference` 唯一约束兜底幂等)，`RefundFactsService` 优先取真实号；**存量数据不迁移、不强制核查**，仅兜底沿用 `refund-{id}` 合成引用 + WARN | 无 | **A3 退款方向已可对**（依赖已消除） |
| **P1** | **A1 账证核对**：三来源双向比对 + `audit_*` 表 + 批次状态机 + 手动触发端点 | 无 | 兑现 SC-005，堵住「漏记账无人知」 |
| **P2** | **A2 账账核对**：借贷平衡纳入批次 + 科目勾稽 + ledger↔settlement 跨账 | P1 | 抓「借贷平衡但科目记错」 |
| **P3** | **A3 账实核对升级**：账本资金科目发生额 ↔ 渠道账单（与现有台账↔账单双轨） | P0 | 真正意义上「账 ↔ 实」 |
| **P4** | **A4 账表核对** + 结算门禁强化 + 报表回算 | P1~P3 | 报表可信；结算前强制账证/账账通过 |
| **P5（v0.2 新增）** | **挂账 / 调账 / 复核对闭环** + demo 控制台接通真实 API | P1 | 差异能真正收口，且**肉眼可演示** |

### 6.1 结算门禁（**分级**，2026-09-06 负责人裁决后细化）

**先澄清口径**：门禁**不报废任何批次**——它拦的是**「结算批次能否创建」**，对账批次本身照常关闭或继续跟进；差异始终**按笔**挂账 / 调账，处理完即可结算。现状（spec 006 / ADR-0023）已是这个语义：`SettlementEligibility` 判定期内存在未处理差异时返回 `false`（`unresolved reconciliation differences present`），拒绝建结算批。

负责人质疑：「状态异常的那笔单独出来调账不行吗，为什么一定要卡整批？」——**成立**。因此门禁按差异**是否已隔离**分级，而不是一刀切：

| 差异状态 | 结算是否放行 | 依据 |
|---|---|---|
| `BLOCKER` 且 `status=PENDING`（**未挂账、未调账**） | ❌ **拦截** | 差额既没查清也没隔离，此时结算存在错付风险 |
| 已 `SUSPENDED`（挂账到 `SUSPENSE`） | ✅ **放行**（留痕 + 指标） | 差额已隔离在过渡科目，**不会进应付商户**，不会错付 |
| 已 `ADJUSTED`（已调账待 recheck） | ✅ **放行**（留痕） | 同上，金额已更正 |
| `MAJOR` / `MINOR` 已挂账 | ✅ **放行** | 同上 |
| 账本借贷不平衡（`GET /internal/ledger/balance` 差额 ≠ 0） | ❌ **拦截** | 硬条件，任何情况都拦 |

**这就是挂账的业务价值**：它把「一笔差异卡住整期结算」变成「隔离后放行、差异继续跟进」。

**实现要点**（P4，依赖 P1/P5）：

- 在 `settlement` 侧 `SettlementEligibility` 增加一级判定：调用新的 `GET /internal/audit/settlement-gate?period=`（reconciliation 侧汇总），返回 `ALLOW` / `BLOCK`，并带 `blockingDifferences` 明细。
- 既有 006 的「关账」判定**保留不动**（NFR-006：不回归 `scenario-reconciliation.sh`）；新判定只做**放宽**——把"已挂账"视为可放行，绝不比现状更严。
- 配置开关 `audit.settlement-gate.enabled`（默认 `true`）；出问题时可关退回现状。
- 指标：`audit.settlement.gate.blocked` / `audit.settlement.gate.passed_with_suspense`。

---

## 7. 挂账与调账（v0.2 新增）

### 7.1 会计语义

| 动作 | 何时用 | 分录（示例） | 效果 |
|---|---|---|---|
| **挂账 SUSPEND** | 差异**一时查不清**（等渠道回单、等人工核单），但差额必须先有"落脚点" | 漏记账方向：借 `CUSTOMER_CASH` X / 贷 `SUSPENSE` X；多记账方向：借 `SUSPENSE` X / 贷 `CUSTOMER_CASH` X | 差额停在过渡科目，**不进损益、不进应付商户**；`SUSPENSE` 余额 = 未查清金额 |
| **调账 SUPPLEMENT** | `MISSING_POSTING`：确认确实漏记，补记 | 借 `CUSTOMER_CASH` X / 贷 `MERCHANT_PAYABLE`(净额)+`PLATFORM_FEE_REVENUE`(手续费) | 补上漏记的账 |
| **调账 REVERSE** | `ORPHAN_POSTING` / `DUPLICATE_POSTING`：确认多记，红冲 | 对原分录做完整反向分录 | 原分录不删，append-only 冲平（FR-003） |
| **调账 CORRECT** | `AMOUNT_MISMATCH` / `DIRECTION_MISMATCH` / 科目记错：红蓝字 | 先反向冲原分录，再记正确分录（两条 posting，同一 adjust_no 关联） | 方向/科目纠正 |
| **调账 TRANSFER** | 挂账后查清了归属 | 借 `SUSPENSE` X / 贷 `MERCHANT_PAYABLE` X（或反向） | `SUSPENSE` 冲平 |
| **调账 WRITE_OFF** | 确认无法追回 / 极小尾差（对手科目待定，见 §11 ⓐ） | 借/贷 损益类科目 | 核销，留痕 |

### 7.2 硬规则（写进代码的约束，逐条有测试）

1. **调账分录必须借贷平衡**（复用 `Posting` 不变量，不平衡直接拒绝）。
2. **必须带 `source_type=ADJUSTMENT`、`source_id=adjustNo`、幂等键 `adjust:{adjustNo}`** —— 重复提交返回首次结果，不产生第二笔分录。
3. **绝不修改/删除既有分录**（append-only，FR-003）。
4. **一笔差异可多次调账，累计金额 MUST ≤ 差异金额**，超额拒绝（`ADJUST_AMOUNT_EXCEEDED`）。
5. **未挂账的差异可直接 SUPPLEMENT / REVERSE / CORRECT**；挂账只用于"查不清"的临时安置，可跳过。
6. **必须 operator + reason**；**复核人为软约束**（2026-09-06 裁决：项目当前单人）——`WRITE_OFF`、金额 > ¥100（10000 分）、缺 reviewer 或 `operator == reviewer` 时**放行但 WARN 留痕**（`audit_adjustments.reviewer` 如实记录，日志/指标打 `audit.adjustment.single_operator`），不阻断流程；后续多人协作时把配置 `audit.review.enforce-double-check`（默认 `false`）切 `true` 即提升为硬拒绝。
7. **只写 audit_* 与 ledger ADJUSTMENT posting，绝不改业务单据状态**（payment/refund/settlement 状态一律不变）。
8. **处置后自动 recheck**：对该 `source_type+source_id` 重跑一次比对，通过则差异置 `VERIFIED`，失败回到 `ADJUSTED` 并重新暴露。
9. **关闭门禁**：存在 `status ∈ {PENDING, SUSPENDED, ADJUSTED}` 的差异时，关批 MUST 拒绝（400）；只有全部 `VERIFIED/RESOLVED` 才允许 `CLOSED`。
10. **SUSPENSE 余额应趋于 0**：期末非零即为"未查清挂账"，纳入 A2 勾稽与看板指标（账表/账账都能看到）。

### 7.3 状态机

```text
batch:   PROCESSING → BALANCED ┐
                    → HAS_DIFFERENCE → RECHECKING → (BALANCED | HAS_DIFFERENCE) → CLOSED
difference: PENDING → SUSPENDED → ADJUSTED → VERIFIED → RESOLVED
              └──────────────┴──────────────┘（任意环节可回退重处置）
adjustment: POSTED → REVERSED（整单红冲，仅限未被 recheck 验证过的）
```

---

## 8. 测试策略 + 模拟数据（v0.2 新增）

### 8.1 分层（沿用项目现有结构：`src/test/java/com/payment/reconciliation/{domain,application,integration}`）

| 层 | 用什么 | 测什么 | 是否起 Spring/DB |
|---|---|---|---|
| **T1 域层单测** | JUnit5 + AssertJ，纯内存 | 比对器（A1/A2 各类差异判定）、挂账/调账策略（§7.2 十条规则）、金额边界（0 / 负数 / 币种不一致 / 超额）、状态机迁移合法性 | 否（快） |
| **T2 应用服务集成测** | `@SpringBootTest` + **H2（MySQL 兼容模式）** + `@MockBean` 三个 facts client 与 ledger 记账网关 | 一个 fixture 一个用例：跑完整批次 → 断言差异数量/类型/金额/状态；调账后 recheck → 差异消失；重复跑幂等 | 是（H2） |
| **T3 架构门禁** | 既有 ArchUnit `ServiceBoundaryTest` | 确认 reconciliation 可依赖 payment/settlement/ledger client；**ledger MUST NOT 反向依赖 reconciliation 或任何业务域** | 否 |
| **T4 API 契约测** | MockMvc | §10.3 的端点：参数校验、404/400/409、响应字段齐全 | 是（H2） |
| **T5 端到端脚本** | `deployment/demo/scenario-audit.sh`（真实 docker MySQL + 全服务） | 见 §9.3 断言清单，失败非零退出 | 真环境 |
| **T6 演示页冒烟** | `audit.html` MOCK 模式 | 触发→执行→结果→挂账→调账→复核→关闭 全链路可点通（本轮手工；后续可脚本化） | 否 |

> **测试纪律（宪法）**：不删测试、不改测试迎合实现；H2 MySQL 兼容模式（不用 Testcontainers，沿用项目现状）；新增用例必须能在 `mvn -o clean verify -fae` 下全绿。

### 8.2 模拟数据集（fixture）设计

统一的确定性 fixture：**单号固定、金额固定、不依赖时间戳**（除 period 外），保证每次跑出来的差异完全一致，便于断言与演示。

**基础样本（平账组，期望 0 差异）**

| # | 业务事实 | 账本 posting |
|---|---|---|
| PM-AUD-0001 | 支付 SUCCEEDED 10000 CNY（手续费 100） | 借 CUSTOMER_CASH 10000 / 贷 MERCHANT_PAYABLE 9900 + PLATFORM_FEE_REVENUE 100 |
| PM-AUD-0002 | 支付 SUCCEEDED 25000 CNY（手续费 250） | 同上比例，24750 + 250 |
| RF-AUD-0001 | 退款 SUCCEEDED 3000 CNY | 借 MERCHANT_PAYABLE 3000 / 贷 CUSTOMER_CASH 3000 |
| SB-AUD-0001 | 结算批次 SUCCEEDED，items 合计 21750 | 借 MERCHANT_PAYABLE 21750 / 贷 SETTLEMENT_PAYABLE 21750 |

**差异样本（每组只注入一种故障，便于精准断言）**

| Fixture | 注入方式 | 期望差异 | 严重级 |
|---|---|---|---|
| **F1** 平账 | 直接灌基础样本 | 0 条，batch=`BALANCED` | — |
| **F2** 漏记账 | 新增 payment `PM-AUD-0003` SUCCEEDED 8000，**故意不调记账接口** | `MISSING_POSTING`（expected 8000 / actual 0） | BLOCKER |
| **F3** 孤儿分录 | 直插 posting `source=PAYMENT/PM-AUD-GHOST1` 5000 | `ORPHAN_POSTING` | BLOCKER |
| **F4** 金额不符 | 篡改基础样本中 PM-AUD-0001 的 posting 金额为 9900（原文 10000） | `AMOUNT_MISMATCH`（10000 vs 9900） | MAJOR |
| **F5** 重复记账 | 对同一 `(PAYMENT, PM-AUD-0002)` 再插一条 posting | `DUPLICATE_POSTING` | BLOCKER |
| **F6** 科目记错（借贷仍平衡） | 把手续费 100 从 `PLATFORM_FEE_REVENUE` 挪到 `MERCHANT_PAYABLE` | A2 `ACCOUNT_RECON_BREAK`（MERCHANT_PAYABLE 勾稽差 100） | MAJOR |
| **F7** 跨账不符 | settlement_items 合计改 21750 → 21000，posting 不动 | `CROSS_LEDGER_MISMATCH` | MAJOR |
| **F8** 账实不符 | 渠道账单多一笔 `CH-AUD-X1` 12000（平台无）/ 少一笔 | `LEDGER_VS_STATEMENT_BREAK` + 现有 `渠道独有/平台独有` | BLOCKER |
| **F9** 账表不符 | 报表接口返回净额与 ledger 回算差 500 | `REPORT_MISMATCH` | MINOR |
| **F10** 全故障混合 | F2~F9 全注入 | 9 类差异各 1 条，batch=`HAS_DIFFERENCE` | 混合 |

**注入方式约定**

- **测试内**（T1/T2）：`AuditFixture` builder 直接构造域对象，不碰 DB；
- **真环境**（T5/demo）：`deployment/demo/fixtures/audit/` 下放两类素材——
  - `audit-faults.sql`：幂等直插/篡改脚本（**仅演示库可用**，脚本首行强制校验 `DB_HOST` 为 localhost/demo）；
  - `2026-08-31.csv` 等周期渠道账单（沿用 `CsvChannelStatementLoader` 的 `{dir}/{period}.csv` 约定，spec 011 ADR-0050）。
- **demo 页 MOCK 模式**（§10）：同一份 fixture 以 JS 常量内置，**离线可演示**，不连任何服务。

---

## 9. 验收标准（v0.2 重写）

> **单一事实源**：SC 的权威清单在 [`spec.md` §验收标准（SC）](spec.md#验收标准sc)；本节只给「SC → 测试层 / 用例」的映射，避免两处漂移。
> 执行方式、DoD 检查表见 [`acceptance.md`](acceptance.md)；用例编号 TC-xx 见 [acceptance.md §3](acceptance.md#3-测试用例矩阵l2)。

| SC | 一句话 | 测试层 | 用例 | fixture |
|---|---|---|---|---|
| SC-001 | 漏记账必报 `MISSING_POSTING` | T2 集成 | TC-02 | F2 |
| SC-002 | 孤儿分录必报 `ORPHAN_POSTING` | T2 集成 | TC-03 | F3 |
| SC-003 | 金额 / 重复 / 科目 / 跨账各报对应类型且不自动修正 | T2 集成 | TC-04~07 | F4~F7 |
| SC-004 | 三来源覆盖率 100%（`checked_count` 一致） | T2 集成 | TC-01 | F1 |
| SC-005 | 科目勾稽推导值 = 账本实算（容差 0） | T2 集成 | TC-06 | F6 |
| SC-006 | 同 `period+scope` 幂等 | T2 集成 | TC-11 | F1 |
| SC-007 | `mvn -o clean verify -fae` 全绿、不引入 MQ / 新服务 | L1 门禁 | — | — |
| SC-008 | 挂账生成平衡分录、业务状态不变 | T2 集成 | TC-14 | F2 |
| SC-009 | `TRANSFER` 后 `SUSPENSE` 归零 | T2 集成 | TC-15 | F2 |
| SC-010 | 调账后 recheck → `VERIFIED` | T2 集成 | TC-16 | F2 |
| SC-011 | 红冲后原分录仍在（append-only） | T2 集成 | TC-17 | F3/F5 |
| SC-012 | 超额调账被拒（`ADJUST_AMOUNT_EXCEEDED`） | T1 单元 | TC-18 | — |
| SC-013 | 同一 `adjust_no` 幂等，只一条 posting | T2 集成 | TC-19 | F2 |
| SC-014 | 双人复核规则（缺 reviewer / 同人 / 大额） | T1 单元 | TC-20 | — |
| SC-015 | 关批门禁（未收口 400 / 全清 200） | T2 集成 | TC-21 | F10 |
| SC-016 | `SUSPENSE` 余额 == Σ 未收口挂账净额 | T2 集成 | TC-15 | F2/F10 |
| SC-017 | 演示页 MOCK / LIVE 全链路可点通 | L4 冒烟 | [acceptance §5](acceptance.md#5-演示页冒烟清单l4--audithtml) | F10 |
| SC-018 | 任意处置序列后试算平衡 Σ(借−贷)=0 | T2 集成 | TC-22 | F10 |

### 9.1 演示脚本断言清单（`deployment/demo/scenario-audit.sh`）

| 步 | 动作 | 断言 |
|---|---|---|
| ① | 灌 `audit-faults.sql`（F2~F9） | 200 / 脚本幂等 |
| ② | `POST /internal/audit/batches {period, scope=CERTIFICATE}` | 200，`batch_no` 以 AB 开头，status=PROCESSING→HAS_DIFFERENCE |
| ③ | `GET /internal/audit/batches/{batchNo}/differences` | 差异数 == fixture 期望值，且包含 MISSING_POSTING/ORPHAN_POSTING |
| ④ | 关批（有未处理差异） | **400** 被拒（门禁生效） |
| ⑤ | 对 F2 差异 `POST .../suspend` | 200，返回 posting_no，差异 status=SUSPENDED |
| ⑥ | 对 F2 差异 `POST .../adjust {kind=TRANSFER→SUPPLEMENT}` | 200，SUSPENSE 归零 |
| ⑦ | `POST /internal/audit/batches/{batchNo}/recheck` | 该差异消失 / 置 VERIFIED |
| ⑧ | 其余差异逐条处置（脚本循环） | 全部 VERIFIED |
| ⑨ | 关批 | 200 → `CLOSED` |
| ⑩ | `GET /internal/ledger/balance` | 各币种差额为 0（借贷平衡不被调账破坏） |

### 9.2 DoD（完成定义）

> 完整检查表见 [`acceptance.md` §6](acceptance.md#6-dod完成定义)；这里只列本 Feature 特有的三条硬门槛：

- [ ] P1~P5 代码 + 测试全部落地，`mvn -o clean verify -fae` 全绿
- [ ] `audit.html` 在 **LIVE 模式**下连通 8088，演示脚本 ①~⑩ 全绿
- [ ] 既有 `scenario-reconciliation.sh` 无回归（`/internal/reconciliation/**` 契约未动）

---

## 10. Demo 对账控制台（v0.2 新增）

### 10.1 放哪、是什么

- **目标落位**：`deployment/mock-channel-web/src/main/resources/static/audit.html`（8091，与 `demo.html` 并列；mock-channel-web 属演示组件，**不进 ArchUnit 服务边界**）。任务编号 T070（[tasks.md](tasks.md)）。
- **当前状态**：原型已完成但**暂不落工程目录**——负责人 2026-09-06 指示「先别着急写代码」，故先以文档附件形式交付：[`audit-console-mockup.html`](audit-console-mockup.html)（离线可直接用浏览器打开演示）。待 §11 决策拍板后由 T070 迁入 `static/`。
- **访问**（迁入后）：演示栈起来后 `http://localhost:8091/audit.html`。
- **两种模式**（页面右上角切换）：
  - **MOCK（默认，本轮已可用）**：内置 §8.2 的 fixture，纯前端模拟异步执行，**离线、不连任何服务即可演示全链路**；
  - **LIVE**：经既有同源代理 `/proxy/{service}/**` 调真实端点（需在 `application.yml` 的 `mock-channel.services` 追加 `recon: http://localhost:8088`、`ledger: http://localhost:8090`、`settlement: http://localhost:8089`）。

### 10.2 页面分区（自上而下，一次演示讲完一个闭环）

| 区 | 内容 | 用户能"看到"什么 |
|---|---|---|
| **① 触发** | scope 下拉（CERTIFICATE / LEDGER / REAL / REPORT）、period 输入（默认 T-1）、数据源选择（平账组 / 全故障组）、「触发对账任务」按钮 | `batchNo`、`status`、`checked/difference` 计数、耗时、进度条 |
| **② 执行日志** | 分步滚动日志：拉取业务事实 N 条 → 拉取分录 M 条 → 双向比对 → 科目勾稽 → 产出差异 K 条 → 状态落定 | **任务真的在跑**（逐行时间线，可暂停/清空） |
| **③ 差异结果** | 汇总条（按 kind 计数）+ 差异表格（勾选 · kind · severity · source_type/id · expected/actual · 状态 chip）+ 按 kind 过滤 | 差异长什么样、严重到什么程度 |
| **④ 挂账** | 选中差异 → 「挂账到 SUSPENSE」→ 预览分录（借/贷科目与金额，实时校验借贷平衡）→ 提交 | 挂账分录 postingNo、差异 → `SUSPENDED` |
| **⑤ 调账** | kind 下拉（SUPPLEMENT/REVERSE/CORRECT/TRANSFER/WRITE_OFF）+ 目标科目 + 金额（自动带出、可改、超差异金额即红线提示）+ operator / reviewer / reason | 调账分录 postingNo、SUSPENSE 余额变化、差异 → `ADJUSTED` |
| **⑥ 复核与关闭** | 「重新核对」→ 已修差异消失并置 `VERIFIED`；「关闭批次」→ 有未处理差异时**红色 400 提示**，全清后 200 `CLOSED` | 闭环真的闭合了 |
| **⑦ 台账实查** | `audit_adjustments` / 挂账调账流水 / 借贷平衡校验结果（模拟 `GET /internal/ledger/balance`） | 每一笔处置都有据可查、借贷恒平衡 |

### 10.3 后端端点契约（LIVE 模式对接；P1/P5 交付）

```text
POST   /internal/audit/batches                                   {period, scope, triggeredBy} → AuditBatchResponse
GET    /internal/audit/batches/{batchNo}                                                      → AuditBatchResponse
GET    /internal/audit/batches/{batchNo}/differences                                          → [AuditDifferenceResponse]
POST   /internal/audit/batches/{batchNo}/differences/{id}/suspend  {operator, reason}          → AuditAdjustmentResponse
POST   /internal/audit/batches/{batchNo}/differences/{id}/adjust   {kind, targetAccount,
                                                                     amountMinor, operator,
                                                                     reviewer, reason}         → AuditAdjustmentResponse
POST   /internal/audit/batches/{batchNo}/recheck                                              → AuditBatchResponse
POST   /internal/audit/batches/{batchNo}/close                    {operator}                  → AuditBatchResponse
GET    /internal/audit/batches/{batchNo}/adjustments                                          → [AuditAdjustmentResponse]
```

- 路径与现有 `/internal/reconciliation/**` 平级，**不动既有 006 的任何端点**（演示脚本 `scenario-reconciliation.sh` 不受影响）。
- 幂等：`POST /batches` 同 `period+scope` 撞唯一键 → 回查已有批次（不新建）。

### 10.4 演示讲法（60 秒）

1. 选「全故障组」+ scope=账证 → 触发 → 日志滚动 → 跳出 9 条差异（MISSING_POSTING 等）；
2. 点 MISSING_POSTING 那条 → 挂账 → 看到 `借 客户资金 8000 / 贷 待处理差错款 8000`；
3. 再调账 SUPPLEMENT → SUSPENSE 归零，差异转 ADJUSTED；
4. 点「重新核对」→ 该差异消失（VERIFIED）；
5. 直接点「关闭批次」→ 红字 400「尚有 N 条未处理差异」；
6. 把剩下的逐条处置完 → 关闭成功 → 底部显示借贷平衡 ✅。

---

## 11. 决策裁决记录（2026-09-06 负责人拍板）

| # | 议题 | **裁决** | 落地位置 |
|---|---|---|---|
| ① | 放哪做 | ✅ **方案 A：扩展 `reconciliation-service`**，新增 `audit` 包，复用批次 / 差异骨架；不新建服务 | §4、tasks 批次 C~G |
| ② | 账实是否升级双轨 | ✅ **升级双轨**——保留「台账 ↔ 渠道账单」，新增「账本资金科目发生额 ↔ 渠道账单」，任一有差异即本期账实不符 | §3 A3、FR-008、tasks 批次 E |
| ③ | 结算门禁 | ✅ **分级门禁（不是一刀切，更不是报废批次）**：`BLOCKER` 且未挂账 → 拦；已挂账 / 已调账 → 放行留痕；借贷不平衡 → 硬拦。详见 §6.1 | §6.1、FR-019 |
| ④ | P0 / N4 | ✅ **N4 已在 spec 016 修复并落库**：退款渠道流水号写入 `payment_attempts`(`attempt_type=REFUND`, `channel_reference` 唯一约束幂等兜底)，`RefundFactsService` 取真实号；**负责人裁决：存量迁移数据不处理（沿用合成引用兜底 + WARN），不单列开发任务**，P0 仅复核确认 | §6 P0、tasks 批次 B(T011/T013/T014) |
| ⑤ | 新增 `SUSPENSE` 科目 | ✅ **批准**（Constitution §8 变更）：`id=5` / `ASSET` / 待处理差错款；枚举 + 幂等 seed，影响面已评估 | §5.1、FR-013、tasks T050 |
| ⑥ | 双人复核 | ✅ **软提示**：单人场景下缺 reviewer / `operator == reviewer` / `WRITE_OFF` / >¥100 **只 WARN 留痕、不阻断**；配置项 `audit.review.enforce-double-check`（默认 false）可一键提升为硬拒绝 | §7.2 规则 6、FR-016 |

**仍需后续确认的小项**（不阻塞实施）：

- ⓐ `WRITE_OFF` 的对手科目：当前科目表无损益 / 费用类科目，核销需确认记到哪里（建议新增 `id=6 LOSS_ON_WRITE_OFF`，或 MVP 先用 `PLATFORM_FEE_REVENUE` 冲减）——**建议 MVP 先不开放 WRITE_OFF 入口**，`audit.adjust.write-off.enabled=false`，需要时再开。
- ⓑ 结算门禁的「已挂账放行」是否需要在结算单上标记「本期含未收口挂账 ¥X」（建议加，信息透明）。

---

## 12. 风险

- **性能**：账证核对要跨服务扫全量事实 + 全量分录。MVP 可接受全量扫描（数据量小）；后续按 `period` 增量 + 索引 `idx_entries_source` / `idx_postings_source` 优化。
- **时点一致性**：跨服务无分布式事务，核对可能读到「正在处理中」的数据 → 建议核对窗口**避开实时交易**（日切后跑 T-1），并对 `PENDING` 状态事实单独归类不判差异。
- **误报**：科目勾稽公式若与业务口径不完全一致会持续误报 → 先以 `MERCHANT_PAYABLE` 一条公式试点，验证通过再铺开。
- **（v0.2）调账是"写"操作，风险高于纯核对**：三重兜底——① 只能走 ledger 标准记账通道且必须平衡；② 金额累计不超差异额；③ reason 留痕 + recheck 验证（单人场景下复核人只做 WARN 提示，见 §11 ⑥）。任何一环失败即拒绝，**绝不自动调账**。
- **（v0.2）演示页不是生产构件**：`audit-faults.sql` 一类"造故障"的素材 MUST 只在 demo 库执行（脚本内置 localhost 校验），且 MOCK 模式的数据纯前端，不进任何库。

---

## 13. 文档清单

| 文件 | 状态 | 说明 |
|---|---|---|
| `docs/specs/017-accounting-audit/spec.md` | ✅ v0.1 Draft | **权威**：US1~US8 / FR-001~025 / NFR-001~008 / SC-001~018 |
| `docs/specs/017-accounting-audit/plan.md` | ✅ v0.2 Draft | 技术方案：口径、架构、数据模型、挂账调账、测试策略、模拟数据、控制台设计 |
| `docs/specs/017-accounting-audit/tasks.md` | ✅ v0.1 | 任务分解（批次 A~J，含依赖图） |
| `docs/specs/017-accounting-audit/acceptance.md` | ✅ v0.1 | 四层验收、TC 用例矩阵、E2E 断言、演示页冒烟、DoD |
| `docs/specs/017-accounting-audit/audit-console-mockup.html` | ✅ 原型 | 离线可点：触发 → 执行 → 差异 → 挂账 → 调账 → 复核 → 关闭；LIVE 模式按 §10.3 契约预留 |

> 本 Feature 截至 2026-09-06 **未写任何业务代码**；`audit-console-mockup.html` 为纯静态文档附件（未放入任何服务模块、不参与构建）。
