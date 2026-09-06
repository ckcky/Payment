# Feature Specification: 会计四核对（账证 / 账账 / 账实 / 账表）与挂账·调账闭环

**Feature Branch**: `017-accounting-audit`

**Created**: 2026-09-06

**Status**: ✅ Accepted（2026-09-06 负责人拍板全部决策点，见 [ADR-0065](../../adr/0026-accounting-audit-suspense-adjustment.md)；**本 Feature 当前仅文档，未写业务代码**，实施自 tasks 批次 C 起）

**Input**: 负责人需求：「账账核对，账证核对，账实核对，还有个账什么核对来着。你看看我们项目需要做哪些怎么搞法」→ 出方案；追加要求：「在里面加上测试和验收的，要有模拟的数据，最好是在 demo 搞个界面能看到对账任务的触发和执行，还有结果，挂账调账这些」。

> 本 Feature **不是从零构建**——`ledger-service` / `reconciliation-service` / `settlement-service` 的核心能力均已落地。本 Spec 是**补齐型 + 闭环型** Spec：① 补上「文档已声明、代码从未实现」的账证核对（spec 004 SC-005）；② 把现有只做一半的账账 / 账实 / 账表补齐；③ 把「只记录差异、不处置」的对账升级为**挂账 → 调账 → 复核 → 关批**的完整会计闭环；④ 配一套确定性模拟数据与演示控制台，让这套能力**肉眼可见、可断言**。

## 当前代码现实（已核实，禁止按绿地项目理解）

| # | 缺口 | 代码 / 文档证据 | 影响 |
|---|---|---|---|
| **G1** | **账证核对完全没有**（覆盖率无校验） | `grep -rn "ledger" reconciliation-service/src/main/java` **零命中**；`docs/specs/004-ledger/spec.md:117` SC-005 明写「覆盖率 100%，**由 reconciliation 校验**」 | 业务已确认但账本漏记（记账 RPC 失败 / 重试耗尽）**无人发现**；反之账本孤儿分录也无人发现 |
| **G2** | 账账核对只有借贷平衡，无科目勾稽 | 仅 `BalanceChecker.isBalanced()`；无「科目余额 = 业务口径推导值」校验 | 分录方向 / 科目记错但借贷仍平衡 → 平衡性校验**查不出来** |
| **G3** | 跨账（ledger ↔ settlement）无核对 | `settlement-service/.../LedgerPostingGateway.java` 只单向记账，不回查 | 结算批次金额与账上「应付→已结」分录可能不一致 |
| **G4** | 账实核对跳过账本 | `PlatformFact` 来自 payment / refund 事实（`PaymentFactsClient` / `RefundFactsClient`），**不含 ledger** | 渠道账单与**资金科目发生额**之间无勾稽 |
| **G5** | 账表核对无 | `settlement_items` / `settlement_adjustments` 无与账本回算的一致性校验 | 结算单 / 报表与账本可能不一致 |
| **G6（已修复 · 原 N4）** | 退款渠道流水号不落库（spec 016 / 8a986a8 已修复落库） | `RefundFactsService` 优先取 `payment_attempts`(`attempt_type=REFUND`) 的 `channel_reference` 真实号；存量数据沿用 `refund-{id}` 合成引用兜底 + WARN（负责人裁决：存量不迁移） | 退款方向现已能按真实渠道退款流水号做账实比对 |
| **G7** | **差异只有"记录"，没有会计处置** | 现有 `ReconciliationApplicationService#resolveDifference` 只写 `resolution_note`，不产生任何分录 | 差异可以"嘴上处理完"，账上差额原地不动；**无挂账、无调账、无复核对**，闭环断裂 |

### 能力现状矩阵（诚实标注，禁止按绿地项目理解）

| 能力 | 状态 | 证据 |
|---|---|---|
| 复式记账 + 借贷平衡（FR-001/002 of 004） | ✅ 已实现 | `ledger/domain/Posting.java`；`09-ledger-schema.sql` |
| 全局 / 分币种平衡校验 | ✅ 已实现 | `BalanceChecker.isBalanced()` / `byCurrency()`；`GET /internal/ledger/balance` |
| 按来源追溯（FR-008 of 004） | ✅ 已实现（单向） | `BalanceChecker.entriesOfSource(...)`；`GET /internal/ledger/entries` |
| 记账幂等 / 分录 append-only | ✅ 已实现 | `postings.uk_postings_idempotency_key`；`ledger_entries` 不可变 |
| 渠道对账（台账 ↔ 渠道账单，4 类差异 + 批次状态机 + 关批门禁） | ✅ 已实现 | spec 006；`ReconciliationController` |
| 结算记账（单向） | ✅ 已实现 | `LedgerPostingGateway` |
| **账证核对（覆盖率）** | ❌ **缺口 G1（且 SC-005 已声明）** | 零实现 |
| **账账：借贷平衡纳入对账批次** | ❌ 缺口 | 平衡性只有端点，未进批次 |
| **账账：科目勾稽 / 跨账** | ❌ 缺口 G2/G3 | 无 |
| **账实：账本 ↔ 渠道账单** | ❌ 缺口 G4 | 账本在对账视野外 |
| **账表核对** | ❌ 缺口 G5 | 无 |
| **挂账 / 调账 / 复核对** | ❌ 缺口 G7 | 只有 `resolution_note` |
| **演示可观测（对账控制台）** | ❌ 缺口 | 仅 `scenario-reconciliation.sh` 脚本，无界面 |

## 目标 / 非目标

**目标**

- **O1**：兑现 spec 004 SC-005——账本对所有已确认资金事实的覆盖率可校验（账证核对），双向报出漏记 / 多记 / 金额 / 币种 / 方向 / 重复六类差异。
- **O2**：账账从「只查借贷平衡」扩展到科目勾稽与 ledger↔settlement 跨账。
- **O3**：账实把账本纳入对账视野（账本资金科目发生额 ↔ 渠道账单），与现有台账↔账单双轨并列。
- **O4**：账表核对（报表 / 结算单回算比对）。
- **O5**：差异可**挂账**（安置到 `SUSPENSE` 过渡科目）、可**调账**（补记 / 红冲 / 更正 / 转出 / 核销）、可**复核验证**，未收口不得关批。
- **O6**：提供**确定性模拟数据**与**演示控制台**，让触发 → 执行 → 结果 → 挂账 → 调账 → 复核 → 关闭 全程可见、可断言。

**非目标**（明确排除，避免范围蔓延）

- ❌ **不自动修钱**：核对与处置都不得自动执行；调账必须人工发起、带原因（Constitution §红线 + 会计内控）。
- ❌ **不改业务状态**：任何挂账 / 调账不得修改 payment / refund / settlement 的业务单据状态，只写 `audit_*` 与 ledger 的 `ADJUSTMENT` 分录。
- ❌ **不新建服务**：扩展 `reconciliation-service`（端口 8088）；ledger 仍只被依赖（Constitution §III、spec 004 FR-005）。
- ❌ **不引入 MQ / 分布式事务**（Constitution §IV、ADR-0001）：沿用既有 `@Scheduled` + 内部同步 RPC。
- ❌ **不做完整财务系统**：无凭证打印、无期间结转、无多账簿、无汇率重估。
- ❌ **不动既有 006 契约**：`/internal/reconciliation/**` 端点与 `scenario-reconciliation.sh` 行为保持不变，新能力走平级的 `/internal/audit/**`。

## User Scenarios & Testing

> 标注约定：`[目标]` = 本 Feature 要建的；无标记 = 现状已有。

### User Story 1 - 账证核对：业务已确认的事实，账本一笔都不能少、也不能多 (Priority: P1)

作为平台财务对账方，我希望系统能按 `(source_type, source_id)` 对**支付 / 退款 / 结算**三来源做**双向**比对，把所有「业务有账无」「账有业务无」「金额/币种/方向不符」「重复记账」的差异一次报出来，使得 spec 004 SC-005 声明的「覆盖率 100%」真正被代码校验，而不是只写在文档里。

**Why this priority**: G1 是唯一一条「文档已声明、代码零实现」的缺口，且后果最严重——记账 RPC 失败导致漏记账时**没有任何机制会发现**。

**Independent Test**: 注入 fixture F2（payment `PM-AUD-0003` SUCCEEDED 8000 但故意不记账）→ 触发 scope=`CERTIFICATE` 批次 → 断言差异列表含 `MISSING_POSTING`，`expected=8000 / actual=0`，且 `checked_count` = 业务侧已确认事实总数。

**Acceptance Scenarios**:

1. **Given** 业务侧有已确认支付 / 退款 / 结算事实，**When** 触发账证核对批次，**Then** 三个来源全部纳入比对，`checked_count` 等于业务侧已确认事实总数（FR-001、FR-003；SC-004）。
2. **Given** 一笔已确认支付在账本无对应 posting，**When** 比对执行，**Then** 产出 `MISSING_POSTING`，`expected`=业务金额、`actual`=0（FR-002；SC-001）。
3. **Given** 账本存在 `(PAYMENT, PM-AUD-GHOST1)` 分录但业务侧无此事实，**When** 比对执行，**Then** 产出 `ORPHAN_POSTING`（FR-002；SC-002）。
4. **Given** 两边金额 / 币种 / 方向不一致，或同一 `(source_type, source_id)` 存在多条 posting，**When** 比对执行，**Then** 分别产出 `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` / `DIRECTION_MISMATCH` / `DUPLICATE_POSTING`，且**核对过程不修正任何数据**（FR-002；SC-003）。
5. **Given** 同一 `period + scope` 重复触发，**When** 第二次请求到达，**Then** 回查已有批次，不产生重复批次与重复差异（FR-003；SC-006）。

### User Story 2 - 账账核对：抓出「借贷平衡但科目记错」 (Priority: P2)

作为平台财务对账方，我希望除了借贷平衡，还能按业务口径推导科目应有余额并与账本实算比对（含 ledger ↔ settlement 跨账），使得「分录方向 / 科目记错但借贷仍平衡」这类错误能被抓出来。

**Why this priority**: 借贷平衡是必要条件不是充分条件；G2 的错误在现有校验下 100% 漏网。

**Independent Test**: 注入 F6（手续费 100 分从 `PLATFORM_FEE_REVENUE` 误记入 `MERCHANT_PAYABLE`，借贷仍平衡）→ 触发 scope=`LEDGER` → 断言产出 `ACCOUNT_RECON_BREAK`，`MERCHANT_PAYABLE` 勾稽差 100 分。

**Acceptance Scenarios**:

1. **Given** 批次开始，**When** 执行账账核对，**Then** 先做分币种借贷平衡校验，差额非 0 产出 `BALANCE_BREAK`（FR-005）。
2. **Given** 科目勾稽公式已定义（至少 `MERCHANT_PAYABLE`；`SUSPENSE` 与未收口挂账互证），**When** 推导值与账本实算不符，**Then** 产出 `ACCOUNT_RECON_BREAK`，容差为 0（FR-006；SC-005）。
3. **Given** 一个结算批次，**When** 其 `settlement_items` 合计与该批次在 ledger 的 posting 金额不符，**Then** 产出 `CROSS_LEDGER_MISMATCH`（FR-007；SC-003）。

### User Story 3 - 账实核对：让账本与外部真金白银对上 (Priority: P3)

作为平台财务对账方，我希望**账本资金科目发生额**也能与渠道账单比对（与现有「业务台账 ↔ 渠道账单」双轨并列），使得「账 ↔ 实」真正成立，而不是只有台账在比。

**Why this priority**: 现状 G4 让账本完全处在对账视野之外——账上记的和银行实际发生的，从来没比过。

**Independent Test**: 注入 F8（渠道账单多一笔 `CH-AUD-X1` 12000，账本无发生额）→ 触发 scope=`REAL` → 断言产出 `LEDGER_VS_STATEMENT_BREAK`。

**Acceptance Scenarios**:

1. **Given** 一个周期的渠道账单已加载，**When** 执行账实核对，**Then** 现有「台账 ↔ 账单」链路**保持不变**并照常产出四类差异（FR-008；不回归 006）。
2. **Given** 账本资金科目在同一周期的发生额，**When** 与渠道账单比对，**Then** 不一致处产出 `LEDGER_VS_STATEMENT_BREAK`（FR-008）。
3. **Given** 退款方向的渠道账单，**When** 比对，**Then** 使用**真实渠道退款流水号**（取自 `payment_attempts` `attempt_type=REFUND` 的 `channel_reference`）匹配；存量无真实号时标记不可用而非按合成引用硬比（FR-008）。

### User Story 4 - 账表核对：报表与账本回算一致 (Priority: P4)

作为平台财务对账方，我希望结算单 / 商户账单 / 对账报表的金额能回算自 ledger + settlement 并与其比对，使得对外输出的报表可信。

**Why this priority**: 优先级最低（G5），但结算门禁（P4）依赖它才能完整。

**Independent Test**: 注入 F9（报表净额与回算差 500 分）→ 触发 scope=`REPORT` → 断言产出 `REPORT_MISMATCH`。

**Acceptance Scenarios**:

1. **Given** 报表输出值，**When** 与账本 + 结算回算值比对不符，**Then** 产出 `REPORT_MISMATCH`（FR-009）。

### User Story 5 - 挂账：查不清的差额先有落脚点 (Priority: P1 / P5)

作为平台会计，我希望对一时查不清的差异，能把差额**挂账**到 `SUSPENSE`（待处理差错款）过渡科目，使得差额有明确落脚点、不进损益也不进应付商户，且随时能看到「还有多少钱挂着没查清」。

**Why this priority**: 没有挂账，差异只能"干等"或"直接调"，两者都不符合会计实务（G7）。

**Independent Test**: 选中 F2 差异 → 挂账 8000 → 断言生成平衡分录（借 `CUSTOMER_CASH` 8000 / 贷 `SUSPENSE` 8000）、`posting_no` 已生成、差异转 `SUSPENDED`、payment 业务状态**未变**、`SUSPENSE` 余额 = 8000。

**Acceptance Scenarios**:

1. **Given** 一条差额 > 0 且状态为 `PENDING`/`ADJUSTED` 的差异，**When** 发起挂账，**Then** 生成**借贷平衡**的过渡分录（账少记：借 `CUSTOMER_CASH` / 贷 `SUSPENSE`；账多记：反向），并记录 `adjust_no` / `posting_no` / 操作人 / 原因（FR-013、FR-014；SC-008）。
2. **Given** 挂账完成，**When** 查询科目余额，**Then** `SUSPENSE` 余额等于所有未收口差异的挂账净额（FR-016、FR-006；SC-016）。
3. **Given** 挂账执行中，**When** 检查业务系统，**Then** payment / refund / settlement 的业务单据状态**一律未被修改**（FR-020；NFR-005）。

### User Story 6 - 调账：查清之后按会计方式定性更正 (Priority: P5)

作为平台会计，我希望对已查清的差异能发起**调账**（补记 / 红冲 / 科目更正 / 从 `SUSPENSE` 转出 / 核销），走标准记账通道生成 `ADJUSTMENT` 分录并全程留痕，使得账实最终一致且每一笔调整都可追溯。

**Why this priority**: 这是闭环的"修"环节；风险最高，因此规则最硬（双人复核、金额上限、幂等、append-only）。

**Independent Test**: 对已挂账的 F2 差异发起 `SUPPLEMENT`（或 `TRANSFER`）→ 断言生成平衡分录、`SUSPENSE` 归零、差异转 `ADJUSTED`；重复提交同一 `adjust_no` 不产生第二笔分录；金额超差异额被拒。

**Acceptance Scenarios**:

1. **Given** 一条差异已查清，**When** 发起 `SUPPLEMENT` / `REVERSE` / `CORRECT` / `TRANSFER` / `WRITE_OFF`，**Then** 生成对应的**借贷平衡**分录，`source_type=ADJUSTMENT`、`source_id=adjustNo`、幂等键 `adjust:{adjustNo}`（FR-015、FR-016；SC-013）。
2. **Given** 调账金额超过差异剩余金额，**When** 提交，**Then** 拒绝并报 `ADJUST_AMOUNT_EXCEEDED`，不产生任何分录（FR-016；SC-012）。
3. **Given** `WRITE_OFF` 或金额 > ¥100，**When** 缺少复核人或操作人与复核人相同，**Then** 拒绝（FR-016；SC-014）。
4. **Given** 原分录存在，**When** 执行红冲 / 更正，**Then** 原分录**不被删除或改写**，只新增反向 / 正确分录（FR-016；SC-011）。
5. **Given** 调账完成，**When** 检查试算平衡，**Then** Σ(借 - 贷) 仍为 0（FR-016；NFR-005）。

### User Story 7 - 复核与关批：处置完必须验证，验证不过不许关 (Priority: P5)

作为平台财务对账方，我希望调账后系统会**自动重新核对**（recheck）该来源，验证通过才置 `VERIFIED`；且只要还有未收口差异，关批 MUST 被拒绝，使得「对账关批」这件事在会计上真的成立。

**Why this priority**: 没有 recheck，调账是否真的把账对平了无人验证；关批门禁是 006 已有的好纪律，本 Feature 必须沿用。

**Independent Test**: 调账后点 recheck → 断言该差异转 `VERIFIED`；在仍有 `PENDING` 差异时关批 → 断言 400 被拒；全部 `VERIFIED` 后关批 → 断言 200 `CLOSED`。

**Acceptance Scenarios**:

1. **Given** 差异已调账且累计金额已覆盖差额，**When** 执行 recheck，**Then** 重跑比对，通过则置 `VERIFIED`，否则退回 `SUSPENDED` 并继续暴露（FR-017）。
2. **Given** 批次中仍存在 `PENDING` / `SUSPENDED` / `ADJUSTED` 差异，**When** 请求关批，**Then** 拒绝（400）并列出未收口差异（FR-018；SC-015）。
3. **Given** 全部差异已 `VERIFIED`，**When** 请求关批，**Then** 批次转 `CLOSED`（FR-018；SC-015）。
4. **Given** A1/A2 通过，**When** 结算发起（P4 门禁加严后），**Then** 才允许结算；否则拒绝（FR-019；§11 决策点 3）。

### User Story 8 - 演示可观测：对账全过程肉眼可见 (Priority: P5)

作为负责人 / 演示受众，我希望在 demo 里有一个界面，能**触发**对账任务、看到**执行过程**、查看**差异结果**，并直接在界面上完成**挂账 / 调账 / 复核 / 关闭**，使得这套会计能力可被演示、被理解、被验收。

**Why this priority**: 需求明确要求「最好是在 demo 搞个界面能看到」；只有脚本断言不足以建立信任。

**Independent Test**: 打开 `http://localhost:8091/audit.html`（MOCK 模式，离线）→ 触发 ALL 批次 → 看到 9 条差异 → 逐条挂账 / 调账 → recheck → 未处理完时关闭被拒 → 全清后关闭成功 → 底部试算平衡显示 Σ=0。

**Acceptance Scenarios**:

1. **Given** 演示栈已启动，**When** 打开对账控制台，**Then** 可选择 scope（账证 / 账账 / 账实 / 账表 / 全部）与 period 并一键触发，页面实时展示 `batchNo` / `status` / 已核对数 / 差异数 / 挂账额 / 调账额与进度（FR-022；SC-017）。
2. **Given** 任务执行中，**When** 观察执行日志区，**Then** 能看到分步进展（拉事实 N 条 → 拉分录 M 条 → 双向比对 → 科目勾稽 → 产出差异 K 条 → 状态落定）（FR-022）。
3. **Given** 差异列表已呈现，**When** 选中一条，**Then** 可预览分录（借 / 贷科目与金额、借贷是否平衡）并提交挂账 / 调账，提交后即时看到 `posting_no`、差异状态流转与 `SUSPENSE` 余额变化（FR-022）。
4. **Given** 后端 `/internal/audit/**` 已交付，**When** 页面切到 LIVE 模式，**Then** 经同源代理 `/proxy/recon/**` 调真实端点，行为与 MOCK 一致（FR-021、FR-023；SC-017）。
5. **Given** 无后端或后端未交付，**When** 使用 MOCK 模式，**Then** 用内置确定性 fixture 在前端完成同样的比对与处置流程，不依赖任何服务（FR-024；SC-017）。

## 功能需求（FR）

### 5.1 核对域

- **FR-001** 系统 MUST 支持按 `(source_type, source_id)` 对**支付 / 退款 / 结算**三来源做双向账证比对（`PAYMENT/PM号`、`REFUND/RF号`、`SETTLEMENT/批次号`，ADR-0063 业务单号口径）。
- **FR-002** 系统 MUST 产出并区分六类账证差异：`MISSING_POSTING` / `ORPHAN_POSTING` / `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` / `DIRECTION_MISMATCH` / `DUPLICATE_POSTING`，且核对过程 MUST NOT 修正任何数据。
- **FR-003** 核对作业 MUST 以批次承载：`batch_no`（`AB`+雪花）、`period`、`scope`、`status`；同一 `(period, scope)` MUST 唯一，重复触发回查首次结果（幂等）。
- **FR-004** 核对 MUST 只读业务域数据（payment / refund / settlement / ledger），MUST NOT 写入任何业务表。
- **FR-005** 账账核对 MUST 将分币种借贷平衡校验纳入批次，差额非 0 产出 `BALANCE_BREAK`。
- **FR-006** 账账核对 MUST 支持科目勾稽（推导应有余额 vs 账本实算，容差 0），至少覆盖 `MERCHANT_PAYABLE`，并使 `SUSPENSE` 余额与未收口差异挂账净额互证；不符产出 `ACCOUNT_RECON_BREAK`。
- **FR-007** 账账核对 MUST 校验 ledger ↔ settlement 跨账一致性，不符产出 `CROSS_LEDGER_MISMATCH`。
- **FR-008** 账实核对 MUST 保留现有「台账 ↔ 渠道账单」链路；并新增「账本资金科目发生额 ↔ 渠道账单」链路，不符产出 `LEDGER_VS_STATEMENT_BREAK`；退款方向 MUST 使用真实渠道退款流水号匹配。
- **FR-009** 账表核对 MUST 将报表输出值与 ledger + settlement 回算值比对，不符产出 `REPORT_MISMATCH`。
- **FR-010** 核对 MUST 支持按 `scope` 参数化执行（`CERTIFICATE` / `LEDGER` / `REAL` / `REPORT`），并提供手动触发端点与 `@Scheduled` 日切 T-1 自动触发（沿用既有调度先例）。
- **FR-011** 每条差异 MUST 带 `severity`（`BLOCKER` / `MAJOR` / `MINOR`），供门禁与演示排序使用。
- **FR-012** 处于 `PENDING` / 处理中的业务事实 MUST 归类为「暂不判定」，不得计为差异（时点一致性）。

### 5.2 挂账 / 调账域

- **FR-013** ledger MUST 新增过渡科目 `SUSPENSE`（待处理差错款，`id=5`，`ASSET`），以幂等 seed 写入 `accounts` 并与 `Account` 枚举对齐（MUST 走 Constitution §8 变更流程）。
- **FR-014** 系统 MUST 支持**挂账**：对差额生成**借贷平衡**的过渡分录（账少记：借 `CUSTOMER_CASH` / 贷 `SUSPENSE`；账多记：反向），并写入 `audit_adjustments` 台账（含 `adjust_no`、`posting_no`、操作人、原因）。
- **FR-015** 系统 MUST 支持五类**调账**：`SUPPLEMENT`（补记）、`REVERSE`（红冲）、`CORRECT`（科目 / 方向更正，红蓝字）、`TRANSFER`（从 `SUSPENSE` 转出）、`WRITE_OFF`（核销）。
- **FR-016** 调账 MUST 强制以下硬规则（逐条需有测试）：① 分录借贷平衡；② `source_type=ADJUSTMENT`、`source_id=adjustNo`、幂等键 `adjust:{adjustNo}`；③ 不删改既有分录（append-only）；④ 累计调账额 ≤ 差异金额；⑤ operator 与 reason 必填；⑥ `WRITE_OFF` 与 >¥100 的调账必须有 reviewer 且 `operator ≠ reviewer`；⑦ 不得修改 payment / refund / settlement 业务状态。
- **FR-017** 处置后 MUST 支持 **recheck**：对该 `source_type + source_id` 重跑比对，通过置 `VERIFIED`，否则退回 `SUSPENDED` 继续暴露。
- **FR-018** 关批 MUST 有门禁：存在 `PENDING` / `SUSPENDED` / `ADJUSTED` 差异时拒绝（400）；全部 `VERIFIED` / `RESOLVED` 才允许 `CLOSED`。
- **FR-019** 系统 MUST 提供挂账 / 调账台账查询（`audit_adjustments`），使每一笔处置可追溯；P4 起结算前置 MUST 校验最近 A1/A2 批次已通过。
- **FR-020** 写操作 MUST 只落在 `audit_*` 表与 ledger 的 `ADJUSTMENT` posting；MUST NOT 写任何其他业务表。

### 5.3 接口与演示

- **FR-021** reconciliation-service MUST 暴露 `/internal/audit/**` 端点（与既有 `/internal/reconciliation/**` 平级，不动 006 契约）：创建批次、查批次、列差异、挂账、调账、recheck、关批、列处置台账（契约见 [plan.md §10.3](plan.md#103-后端端点契约live-模式对接p1p5-交付)）。
- **FR-022** 演示组件 `mock-channel-web`（8091）MUST 新增静态页 `audit.html`（对账控制台），包含：触发区、执行日志区、差异结果区、挂账区、调账区、复核与关闭区、台账与试算平衡区；并支持 **MOCK / LIVE** 双模式。
- **FR-023** `mock-channel-web` 的 `application.yml` MUST 追加 `recon` / `ledger` / `settlement` 服务映射，使 `/proxy/{service}/**` 可透传（演示组件内改动，不改生产契约）。
- **FR-024** 系统 MUST 提供**确定性模拟数据集**（fixture F1~F10：平账组 + 8 类故障 + 全故障混合），单号与金额固定，使每次执行的差异结果完全一致，供测试断言与演示复用（定义见 [plan.md §8.2](plan.md#82-模拟数据集fixture设计)）。
- **FR-025** 演示脚本 `deployment/demo/scenario-audit.sh` MUST 覆盖「灌 fixture → 触发 → 列差异 → 关批被拒（400）→ 挂账 → 调账 → recheck → 关批成功 → 借贷平衡校验」，失败即非零退出（脚本纪律 ADR-0051）。

## 非功能需求（NFR）

- **NFR-001 性能**：MVP 允许全量扫描（数据量小）；单批次在演示数据量下应秒级完成；后续按 `period` 增量 + `idx_entries_source` / `idx_postings_source` 优化。
- **NFR-002 时点一致性**：跨服务无分布式事务 → 核对窗口 MUST 避开实时交易（日切后跑 T-1）；`PENDING` 事实不判差异（FR-012）。
- **NFR-003 可观测**：MUST 产出批次耗时、差异计数（按 kind / severity）、`SUSPENSE` 余额、处置次数等指标与结构化日志，便于看板与告警。
- **NFR-004 内控**：所有挂账 / 调账 MUST 留痕（谁、何时、何种、金额、原因、复核人）；双人复核规则由 FR-016 强制。
- **NFR-005 不自动修钱**：系统 MUST NOT 因核对结果自动调账 / 自动退款 / 自动改单；任何"修"必须人工发起。
- **NFR-006 契约稳定**：`/internal/reconciliation/**` 与 `scenario-reconciliation.sh` 行为 MUST NOT 回归。
- **NFR-007 测试与构建**：新增用例 MUST 在 H2（MySQL 兼容模式）下运行；`mvn -o clean verify -fae` MUST 全绿；不引入 MQ / 分布式事务 / 新服务。
- **NFR-008 失效安全**：任一数据源不可达时，批次 MUST 标记失败并明确原因，MUST NOT 静默产出"无差异"。

## 验收标准（SC）

> 权威清单在本节；[plan.md §9](plan.md#9-验收标准v02-重写) 给出测试分层映射，[acceptance.md](acceptance.md) 给出执行方式与 DoD 检查表。

- **SC-001**：F2（漏记账）→ 100% 报出 `MISSING_POSTING`，金额与业务侧一致。
- **SC-002**：F3（孤儿分录）→ 100% 报出 `ORPHAN_POSTING`。
- **SC-003**：F4 / F5 / F6 / F7 → 分别报出 `AMOUNT_MISMATCH` / `DUPLICATE_POSTING` / `ACCOUNT_RECON_BREAK` / `CROSS_LEDGER_MISMATCH`，且核对过程不修正任何数据。
- **SC-004**：A1 对三来源覆盖率 100%，`checked_count` = 业务侧已确认事实总数。
- **SC-005**：科目勾稽按业务口径推导 = 账本实算（容差 0）。
- **SC-006**：幂等——同 `period + scope` 重复执行不产生重复批次 / 重复差异。
- **SC-007**：`mvn -o clean verify -fae` 全绿；不引入 MQ / 分布式事务 / 新服务。
- **SC-008**：对 F2 执行挂账 → 生成平衡分录（借 `CUSTOMER_CASH` 8000 / 贷 `SUSPENSE` 8000），`posting_no` 已生成，差异转 `SUSPENDED`，业务单据状态不变。
- **SC-009**：对已挂账差异执行 `TRANSFER` → `SUSPENSE` 余额回落到 0。
- **SC-010**：`SUPPLEMENT` 补记后自动 recheck → 该 `MISSING_POSTING` 消失，差异置 `VERIFIED`。
- **SC-011**：`REVERSE` 红冲后原分录仍在（`ledger_entries` 只增不减），该 source 净额归零。
- **SC-012**：调账金额超过差异金额 → 拒绝（`ADJUST_AMOUNT_EXCEEDED`），不产生分录。
- **SC-013**：同一 `adjust_no` 重复提交 → 返回首次结果，账上只有一条 posting。
- **SC-014**：`WRITE_OFF` 缺 reviewer、或 `operator == reviewer`、或 >¥100 缺 reviewer → 拒绝。
- **SC-015**：存在 `PENDING` / `SUSPENDED` / `ADJUSTED` 差异时关批 → 400；全部 `VERIFIED` 后关批 → 200 `CLOSED`。
- **SC-016**：任意时刻 `SUSPENSE` 科目余额 == Σ 未收口差异挂账净额（勾稽恒等）。
- **SC-017**：演示页 MOCK 模式全链路可点通（触发 → 执行 → 结果 → 挂账 → 调账 → 复核 → 关闭），且试算平衡 Σ=0；LIVE 模式在后端交付后行为一致。
- **SC-018**：试算平衡：任意处置序列之后，Σ(借 - 贷) 恒为 0。

## 依赖与前置

| 依赖 | 说明 | 状态 |
|---|---|---|
| **G6 / N4 退款渠道流水号** | 账实在退款方向的前提；spec 016 / 8a986a8 已落库修复（`payment_attempts.attempt_type=REFUND` + `channel_reference` 唯一约束） | ✅ 已修复，复核确认通过；存量数据不迁移 |
| **spec 004 SC-005** | 本 Feature 兑现该条验收标准 | 引用 |
| **spec 006 对账骨架** | 批次状态机、差异模型、关批门禁、只读 facts 客户端复用 | 已有 |
| **spec 007 settlement** | 跨账核对与结算门禁的对接方 | 已有 |
| **Constitution §8** | 新增 `SUSPENSE` 科目属 Schema / 领域模型变更，MUST 走提案 + 人类确认 | 待拍板 |
| **ADR-0054 / spec 016** | 领域分层后 payment 为能力提供方，本 Feature 新增的读取面不得反向影响该边界 | 引用 |

## 风险

- **性能**：账证核对跨服务扫全量事实 + 全量分录（NFR-001 缓解）。
- **时点一致性**：无分布式事务，可能读到处理中数据（NFR-002 缓解）。
- **误报**：科目勾稽公式与业务口径不完全一致会持续误报 → 先以 `MERCHANT_PAYABLE` 一条公式试点。
- **调账是"写"操作**：风险高于纯核对 → 三重兜底（必须平衡 / 金额不超差异额 / 双人 + 原因 + recheck 验证），任何一环失败即拒绝，**绝不自动调账**。
- **演示素材误入生产**：`audit-faults.sql` 一类"造故障"素材 MUST 只在 demo 库执行（脚本内置 localhost 校验），MOCK 数据纯前端不落库。

## 关联文档

- 技术方案 / 数据模型 / 测试策略 / 模拟数据 / 演示控制台设计：[plan.md](plan.md)
- 任务分解：[tasks.md](tasks.md)
- 验收执行与 DoD：[acceptance.md](acceptance.md)
- 界面原型（离线可点，文档附件）：[audit-console-mockup.html](audit-console-mockup.html)
- 相关 Spec：004-ledger、006-reconciliation、007-settlement、011-demo-showcase、016-order-payment-orchestration
- 相关 ADR：ADR-0001（无 MQ / 分布式事务）、ADR-0054（领域分层）；本项目已立项 ADR-0065（挂账 / 调账与复核对闭环，2026-09-06 负责人拍板）
