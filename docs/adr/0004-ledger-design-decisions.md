# ADR-0008 ~ ADR-0011：Ledger 资金账本（Feature 004）架构决策集合

> 本文件合并原 `0008`~`0011` 四份决策为单一记录，便于集中审阅与演进。
> 编号为内部决策标签（ADR-0008 ~ ADR-0011）保持不变，状态独立标注。
> 涉及 Constitution §8「人类决策边界」（新增服务、数据所有权、领域模型、资金表）的决策，均**待负责人确认**后生效。

> **2026-08-29 负责人裁决**：ADR-0008~0011 **全部 Accepted**；其中 **ADR-0010 改为「金额只用分」**
> （否决 Money VO 路线，详见该条目）。见 `docs/adr/0004` 各条目末尾的「负责人裁决」小节。

---

## ADR-0008: Ledger 数据模型（复式记账 + 科目/分录结构）

- **状态**：**Accepted**（2026-08-29 负责人确认）
- **日期**：2026-08-28
- **决策者**：人类（项目 Owner）
- **关联 Feature**：`004-ledger`（spec FR-001~FR-003、data-model.md）

### Context（背景）

Constitution §II.3 规定「任何资金变动 MUST 经 ledger-service 复式记账，借贷必须平衡」。在 Ledger 落地前，支付成功仅是 `payment-service` 内部状态，无法回答「平台收了多少、欠商户多少、平台赚多少手续费」。需要决定：**账本如何建模才能满足复式、平衡、可追溯三要素**。

### Decision（决策 · 推荐方案）

采用 **`Account` + `Posting`（聚合根）+ `LedgerEntry`（不可变分录）** 模型：

1. **Account（科目）**：系统预置固定科目表（Chart of Accounts）：`CUSTOMER_CASH`(ASSET)、`MERCHANT_PAYABLE`(LIABILITY)、`PLATFORM_FEE_REVENUE`(REVENUE)、`SETTLEMENT_PAYABLE`(LIABILITY)；MVP 单币种（CNY）。
2. **Posting（记账批次）**：一次业务事件对应的一组平衡分录容器；持有 `idempotency_key`/`source_type`/`source_id`/`status`；其下 `LedgerEntry` 借贷**必须**相等（同币种）。
3. **LedgerEntry（分录）**：单条借贷记录，**不可变**（append-only，不 UPDATE/DELETE；更正只能新增反向冲正）；含 `account_id`/`direction`/`amount_minor`/`currency`/`entry_type`/`source_type`/`source_id`。

> 推荐 **Accepted**。理由：直接满足 Constitution §II.3 三要素，且 `LedgerEntry` 不可变 + 来源冗余字段天然支持追溯与对账。

### 备选方案

- **A. 单式余额表**：仅记余额变动，最简单，但无法满足「复式」「借贷平衡校验」「可追溯」，违反 Constitution（否决）。
- **B. 事件溯源（Event Sourcing）**：能力最强，但超出当前架构（Constitution §4 禁止为炫技引入复杂仪式；MQ/CQRS 留待 Phase 10 评估），MVP 不采用（否决）。
- **C. 复式记账 + append-only 分录（采纳）**：显式满足铁律，复杂度可控。

### Consequences（后果）

**正面**：复式平衡可校验、来源可追溯、与 Constitution §II.3 一致。
**代价**：需预置科目表与业务→科目映射；分录不可变意味着更正走冲正（与退款同机制），增加少量设计约束。

### 关联

- Constitution §II.3、§III（Ledger 只被依赖）
- `004-ledger` spec / data-model.md
- `docs/architecture/roadmap.md` Phase 8

---

## ADR-0009: 记账触发与一致性（同步 RPC 幂等记账 + 失败兜底）

- **状态**：**Accepted**（2026-08-29 负责人确认）
- **日期**：2026-08-28
- **决策者**：人类（项目 Owner）
- **关联 Feature**：`004-ledger`（spec FR-006、FR-010；data-model.md §6）

### Context（背景）

支付成功后**何时、如何**把事实写入账本？尤其：账本服务不可用/超时时，是否回滚支付成功？这关系到 Constitution §V（一致性）与 §IV（禁 2PC、当前无 MQ）的硬约束。

### Decision（决策 · 推荐方案）

1. **触发时机**：各业务服务在资金事实到达**已确认**状态（支付 `SUCCEEDED`、退款确认、结算批次确认）**之后**，通过**同步 RPC（OpenFeign）**调用 `ledger-service.postEntries`（携带幂等键）。
2. **失败不回滚业务事实**：账本失败 **MUST NOT** 回滚支付/退款/结算成功事实（Saga 语义，禁 2PC/XA）。
3. **重试与兜底**：调用方 `LedgerPostingGateway`（Feign，沿用既有 `ResilientFulfillmentGateway` 模式）对**幂等**记账 RPC 做有限退避重试；重试耗尽仍失败 → 记录 `ledger.posting_failed` 指标 + `FINANCIAL_AUDIT` 告警 + 进入「待记账」清单，由 **reconciliation 对账补齐**（不回滚、不阻塞业务）。
4. **未确认不记账**：UNKNOWN/PROCESSING 不发起记账（Constitution §V.7）。

> 推荐 **Accepted**。理由：与 ADR-0001（无 MQ、同步 RPC）、§IV（Saga+幂等、禁 2PC）、§V（终态/幂等）完全一致，且失败兜底可观测、可收敛。

### 备选方案

- **A. 业务库 + 账本库 2PC/XA**：强一致但违反 Constitution §4 禁止清单（否决）。
- **B. MQ 异步事件驱动记账**：需引入 MQ（当前无，ADR-0001），运维负担重（否决，留待 Phase 10 评估）。
- **C. 同步 RPC + 幂等 + 重试/对账兜底（采纳）**。

### Consequences（后果）

**正面**：不破坏既有架构；业务事实不丢；账本缺口可被对账发现并补齐。
**代价**：账本短暂「落后」于业务（最终一致）；需保证 reconciliation 能比对「业务已确认 vs 账本已记账」的孤儿事实。

### 关联

- ADR-0001（同步 RPC、当前无 MQ）、Constitution §IV/§V
- `004-ledger` spec FR-006/FR-010、data-model.md §6
- `payment-service` 既有 `ResilientFulfillmentGateway` 模式

---

## ADR-0010: 金额表示（Ledger 启用 Money 值对象 vs 仅 long 分）

- **状态**：**Accepted（已修订，2026-08-29 负责人确认）**
- **日期**：2026-08-28
- **决策者**：人类（项目 Owner）
- **关联 Feature**：`004-ledger`（spec FR-009；审计 P0-1「Money VO 死代码」）

### Context（背景）

Constitution §II.2 要求「封装 `Money` 值对象（金额+币种），禁止裸 long 满天飞」，但审计（2026-08-28）发现 `Money` VO 当前是**死代码**（零引用）。Ledger 作为「资金正确性核心」，应率先示范金额封装。需要决定：**Ledger 内部金额用 Money VO 还是 bare long 分**。

### Decision（决策 · 负责人裁决）

**金额一律只用「分」（`long` 最小货币单位），不引入 `Money` 值对象。**

- 所有金额的字段、参数、DTO、落库列统一为 `long` + 独立 `currencyCode` 字段（现状即如此，保持不变）。
- `common-core` 的 `Money` VO **不启用**；它是零引用死代码（审计 P0-1），本决策下按「未采纳方案」保留源码与单测，
  但**不得**在 `src/main` 中新增引用。

> 负责人裁决理由（2026-08-29）：Money 封装带来的类型安全收益，小于它在 ~50 处调用点引入的转换成本与
> 认知负担；`long 分 + 显式币种` 已是支付行业通行且够用的表示， Constitution §II.1 禁止 `float/double`
> 的硬红线靠命名约定（`*Minor` 后缀）+ 代码评审即可守住。

### 备选方案

- **A. 全仓立即改用 Money**：正确性最佳，但触碰 ~50 处（审计 P0-1），范围与风险过大（否决）。
- **B. 金额只用 `long` 分（采纳）**：零改动、语义明确，与 Constitution §II.1 一致。
- **C. Ledger 启用 Money、其余沿用 long 分**：半吊子状态，两套表示并存最易出错（否决）。

### Consequences（后果）

**正面**：金额表示全仓唯一，无转换成本，`*Minor` 后缀 + 币种字段即可追溯口径。
**代价**：失去编译期的「金额+币种」绑定保护；靠命名约定与评审守红线（已写入本 ADR 与 Constitution §II.1 检查项）。
**残留项**：`Money` VO 及其单测成为未采纳的死代码，后续若裁员或重审可整包删除（不阻塞任何 Feature）。

### 关联

- Constitution §II.1/§II.2
- 审计 2026-08-28 `P0-1`：`Money` VO 死代码
- `common-core/.../money/Money.java`

---

## ADR-0011: MVP 记账范围（支付 / 退款 / 结算哪些首批）

- **状态**：**Accepted**（2026-08-29 负责人确认）
- **日期**：2026-08-28
- **决策者**：人类（项目 Owner）
- **关联 Feature**：`004-ledger`（spec US1~US3；data-model.md §5）

### Context（背景）

Ledger 可覆盖支付、退款、结算、渠道清算等多种资金变动。MVP 应覆盖哪些，才能既满足「账本覆盖所有已确认资金事实」（审计 D1 的根本诉求）又不过度膨胀。

### Decision（决策 · 推荐方案）

**支付成功 + 退款首批（US1/US2）；结算跟随本 Feature（US3）**。三者均属 Roadmap Phase 8 既定范围。渠道清算（Channel Clearing 科目）属后续，本 MVP 不做。

> 推荐 **Accepted**。理由：支付+退款是直接资金流，结算是闭环最后一环；三者合计即「所有已确认资金事实」，满足 D1 诉求，且范围收敛。

### 备选方案

- **A. 仅支付成功**：范围过小，退款/结算仍无账本覆盖（否决）。
- **B. 支付+退款+结算+渠道清算**：范围过大，渠道清算属 Phase 后续（否决）。
- **C. 支付+退款首批、结算跟随（采纳）**。

### Consequences（后果）

**正面**：MVP 即覆盖全部已确认资金事实，D1 矛盾实质性消除；范围可控。
**代价**：需三个调用方（payment/refund/settlement）各实现 `LedgerPostingGateway`，工作量略增。

### 关联

- `004-ledger` spec US1~US3、data-model.md §5
- `docs/architecture/roadmap.md` Phase 8
