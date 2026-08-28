# Feature Specification: Ledger 资金账本（复式记账）

**Feature Branch**: `004-ledger`

**Created**: 2026-08-28

**Status**: Draft（设计决策见 `docs/adr/0004-ledger-design-decisions.md`，ADR-0008~0011 待负责人决策）

**Input**: 用户描述：审计发现 Constitution §II.3（一切资金变动 MUST 经 `ledger-service` 复式记账）与 Roadmap（Ledger 延后到 Phase 8）自相矛盾（审计 D1）。用户决策：**先把 ledger 前置实现**，按 Spec Kit 流程「文档先行」。本 Spec 解决该矛盾，把 Ledger 从「延后」改为「当前 Feature」。

> 本 Feature 对应 `docs/architecture/roadmap.md` 的 **Phase 8 · Ledger**，但因负责人决策**前置**到当前迭代（原 002/003 之后立即启动），故物理目录采用顺序编号 `004-ledger`（与 Roadmap 阶段标签解耦，遵循 `003-payment-reliability` 既定约定）。
> 所有开放性设计分歧点已落到 ADR-0008~0011（状态 **Proposed**，供负责人按 Constitution §8 确认）。实现前 MUST 先确认这 4 条 ADR。

## User Scenarios & Testing

### User Story 1 - 支付成功在账本中留下平衡的分录 (Priority: P1)

作为平台，我希望每一笔被确认为成功的支付，都在 `ledger-service` 中留下一组**借贷平衡**的复式分录，从而让「资金事实」从「Payment 状态机上的标记」升级为「可追溯、可审计、可加总的账务事实」，满足 Constitution §II.3。

**Why this priority**: 这是 Ledger 存在的根本理由。在 Ledger 落地前，支付成功只是 `payment-service` 内部的一个状态，无法回答「平台总共收了多少钱、欠商户多少、平台赚了哪些手续费」。先把支付入账，才能为退款、对账、结算提供可信的账务底座。

**Independent Test**: 使一笔支付进入 `SUCCEEDED`，触发账本记账，断言 `ledger-service` 中该支付对应 `Posting` 的借贷合计相等（`sum(debit) == sum(credit)`），且可通过 `source_id` 回查到该 Posting 与对应分录。

**Acceptance Scenarios**:

1. **Given** 一笔支付被确认为 `SUCCEEDED`（金额 A，手续费 F，净额 N=A-F），**When** 记账请求到达 `ledger-service`，**Then** 生成一条 `Posting`，包含至少两条 `LedgerEntry`，借贷平衡，且来源标记为 `PAYMENT:<paymentId>`。
2. **Given** 同一笔支付因重试/重复回调被二次发起记账，**When** 请求携带相同幂等键，**Then** `ledger-service` 返回首次的 `Posting`（幂等），不产生重复分录（借贷仍平衡）。
3. **Given** 记账请求的借贷不相等（如金额字段被篡改或构造错误），**When** 提交，**Then** `ledger-service` 拒绝该 `Posting`（`UNBALANCED` 校验失败），不落任何分录。

---

### User Story 2 - 退款在账本中冲正（平衡的反向分录） (Priority: P1)

作为平台，我希望每一笔被确认的退款，都在账本中留下与原始支付「对账目影响相反」的平衡分录（冲正），从而让「商户应付」「平台手续费」等科目随退款正确回落，保持全局借贷平衡。

**Why this priority**: 退款是资金逆向流动，若只改 Payment/Refund 状态而不动账本，账本会「虚高」（仍记着已退资金的负债）。退款记账与支付记账同一模型，是 Ledger 价值最直接的延伸。

**Independent Test**: 对一笔已记账的支付发起退款（金额 R），断言账本新增一条来源为 `REFUND:<refundId>` 的平衡 Posting，且「商户应付」科目因该退款减少 R（与支付记账方向相反）。

**Acceptance Scenarios**:

1. **Given** 一笔已记账支付（商户应付 +N），**When** 退款 R 被确认，**Then** 账本生成冲正 Posting（DEBIT 商户应付 R / CREDIT 客户资金 R），全局仍平衡。
2. **Given** 同一退款被重复提交，**When** 携带相同幂等键，**Then** 幂等返回首次 Posting，不重复冲正。

---

### User Story 3 - 结算在账本中结转账目（应付→已结） (Priority: P2)

作为平台，我希望每个商户结算周期生成结算批次时，账本将该商户的「商户应付」结转为「结算应付/已结算」，从而把「待结算负债」与「已结算负债」区分开，为未来真实出款预留清晰的账务边界（本 MVP 不真实出款，见 Constitution §II/Roadmap Phase 7）。

**Why this priority**: 结算是资金闭环的最后一环。先建立「应付→已结」的记账边界，即便 MVP 不出款，也能让账本在时间维度上自洽，避免把「已结算」与「待结算」混为一谈。

**Independent Test**: 对一个商户周期结算批次（净额 S）触发记账，断言账本生成来源为 `SETTLEMENT:<batchId>` 的平衡 Posting（DEBIT 商户应付 S / CREDIT 结算应付 S），且该商户「商户应付」科目减少 S。

**Acceptance Scenarios**:

1. **Given** 商户有已确认待结算净额 S，**When** 结算批次生成并触发记账，**Then** 账本生成平衡 Posting，商户应付减少 S、结算应付增加 S。
2. **Given** 同一结算批次被重复结算，**When** 携带相同幂等键，**Then** 幂等返回首次 Posting。

---

### User Story 4 - 账本事实可被对账校验（借贷恒等 + 来源可追溯） (Priority: P2)

作为资金风控/审计人员，我希望账本具备「任意时刻全局借贷平衡」与「每笔业务事实可追溯到平衡分录」的不变量，从而让对账（reconciliation-service）能校验「业务事实（Payment/Refund/Settlement）」与「账务事实（Ledger）」是否一致。

**Why this priority**: Constitution §II.3 的底层诉求是「可追溯、可审计」。账本若不提供平衡性校验与来源追溯，就退化成另一个状态表，失去复式记账的意义。

**Independent Test**: 在任意记账序列后，断言 `ledger-service` 的全局校验接口返回「平衡」；对任意一条 `LedgerEntry` 都能沿 `source_type/source_id` 找到其业务来源，并验证该来源确实存在于对应服务。

**Acceptance Scenarios**:

1. **Given** 任意 N 笔成功记账后，**When** 调用平衡性校验，**Then** 返回所有科目借贷合计相等（差额为 0）。
2. **Given** 某条分录来源 `PAYMENT:p1`，**When** 查询，**Then** 可定位到 payment-service 中 paymentId=p1 的已确认支付事实。

---

### Edge Cases

- **记账请求在途时支付状态变化**：账本只认「已确认」的业务事实；`payment-service` 仅在支付到达 `SUCCEEDED` 后才发起记账，UNKNOWN/PROCESSING 不记账（Constitution §V.7：未确认结果不直接记账）。
- **账本服务不可用 / 记账超时**：不回滚支付成功事实（Saga 语义，禁 2PC）。支付侧记录「记账待重试」并通过重试/对账兜底（详见 ADR-0009、data-model §重试与兜底）。
- **重复/乱序记账**：以幂等键（DB 唯一约束）吸收重复；迟到/乱序的记账请求若幂等键已存在，直接返回首次结果，不重复入账。
- **借贷不平衡的请求**：在账本服务内做 `sum(debit)==sum(credit)` 强校验，拒绝落库（属于数据质量门禁，不是业务错误）。
- **金额溢出/精度**：金额一律最小货币单位 `long` 分或 `BigDecimal`（明确 scale），禁止 `float/double`（Constitution §II.1）；具体表示方式见 ADR-0010。
- **多币种**：MVP 单币种（CNY）起步；科目按币种维度隔离，多币种清分属 `[Phase 后续延后]`（Roadmap Phase 8 不包含）。

## Requirements

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `ledger-service`，对每笔**已确认**的资金变动（支付成功、退款、结算）生成一组借贷平衡的复式分录（`Posting` + `LedgerEntry`）。
- **FR-002**: 每笔 `Posting` MUST 满足 `sum(debit amount) == sum(credit amount)`（以币种为维度）；不满足的 Posting MUST 被拒绝，不落任何分录。
- **FR-003**: 每笔 `LedgerEntry` MUST 记录方向（DEBIT/CREDIT）、科目、金额、币种、来源类型与来源 ID，且 MUST 不可变（append-only，不更新、不删除已提交分录）。
- **FR-004**: 记账入口 MUST 接受调用方提供的幂等键，并以数据库唯一约束兜底；相同幂等键的重复请求 MUST 返回首次结果，不重复生成分录。
- **FR-005**: `ledger-service` MUST 仅被其他服务调用（作为被依赖方），MUST NOT 反向依赖任何业务领域（Constitution §III 依赖方向）。
- **FR-006**: 支付成功 MUST 触发对 `ledger-service` 的记账（经幂等 RPC）；记账失败 MUST NOT 回滚支付成功事实，MUST 进入重试/对账兜底路径（Saga + 幂等，禁 2PC/XA）。
- **FR-007**: 账本 MUST 提供全局借贷平衡性校验能力（供 reconciliation / 运维使用）。
- **FR-008**: 任意 `LedgerEntry` MUST 可通过 `source_type` + `source_id` 追溯到其业务来源；账本 MUST NOT 修改业务服务的原始事实。
- **FR-009**: 金额表示 MUST 遵守 Constitution §II.1（最小货币单位 `long` 分或 `BigDecimal`，禁 `float/double`）；具体是否启用 `Money` 值对象见 ADR-0010。
- **FR-010**: 跨服务调用 `ledger-service` MUST 沿用既有同步 RPC（OpenFeign）+ 幂等模式，不引入 MQ / 跨服务异步事件（Constitution §IV、ADR-0001）。
- **FR-011**: 账本 MUST 具备资金审计日志（`FINANCIAL_AUDIT`），记录每次成功记账的来源、金额、科目与前后余额摘要。

### Key Entities

- **Account（科目）**: 复式记账的账户，含 `code`/`name`/`type`(ASSET/LIABILITY/REVENUE/EXPENSE/EQUITY)/`currency`；MVP 为系统预置的固定科目表（Chart of Accounts），可按来源自动映射（具体科目设计见 ADR-0008）。
- **Posting（记账批次）**: 一次业务事件对应的一组平衡分录的容器；含 `idempotency_key`/`source_type`/`source_id`/`status`(POSTED)；其下 `LedgerEntry` 借贷必须平衡。
- **LedgerEntry（分录）**: 单条借贷记录，不可变；含 `account_id`/`direction`/`amount_minor`/`currency`/`entry_type`(PAYMENT_CAPTURE/REFUND/SETTLEMENT/FEE...)/`posting_id`/`source_type`/`source_id`。
- **LedgerPostingGateway（payment/refund/settlement 侧）**: 各业务服务内的出站网关（类似既有 `ResilientFulfillmentGateway`），包裹对 `ledger-service` 的 Feign 调用，提供超时/重试/兜底（ADR-0009）。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 每笔 `SUCCEEDED` 支付 100% 在账本留下平衡分录；重复记账 100% 被幂等吸收，不产生重复分录。
- **SC-002**: 每笔确认退款 100% 生成平衡冲正分录，全局借贷仍平衡。
- **SC-003**: 每个结算批次 100% 在账本生成「应付→已结」平衡 Posting。
- **SC-004**: 任意时刻 `ledger-service` 全局借贷平衡性校验返回「平衡」（差额恒为 0）。
- **SC-005**: 账本对所有已确认资金事实的覆盖率 100%（无「业务已确认但账本无分录」的孤儿事实，由 reconciliation 校验）。

## Assumptions

- 本 Feature 不引入 MQ / 分布式事务；记账通过同步 RPC + 幂等 + 重试/对账兜底（Constitution §IV、ADR-0001、ADR-0009）。
- 当前为单节点/单机部署；`ledger-service` 为独立进程、独立端口（建议 8090）、独立 Schema（`ledger`）。
- MVP 单币种（CNY）；多币种清分、复杂会计准则、全面财务总账不在本 Feature（Roadmap Phase 8 不包含）。
- 记账触发方（payment/refund/settlement）在各自「已确认」状态后发起；未确认（UNKNOWN/PROCESSING）不记账。
- 具体科目表、记账触发与一致性模型、金额表示、MVP 记账范围，均见 ADR-0008~0011（待负责人确认）。
- 本 Feature 复用既有工程底座（`common-core` 的 `BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`、MyBatis-Plus、OpenFeign）。

## Clarifications

### Session 2026-08-28

本 Feature 由审计 D1（Constitution §II.3 与 Roadmap 延后 Ledger 的矛盾）驱动，负责人决策：**前置实现 Ledger，文档先行**。分歧点已落到 `docs/adr/0004-ledger-design-decisions.md`：

- **ADR-0008**（Ledger 数据模型：复式记账 + 科目/分录结构）→ **Proposed**，建议 Accepted。
- **ADR-0009**（记账触发与一致性：同步 RPC 幂等记账 + 失败重试/对账兜底）→ **Proposed**，建议 Accepted。
- **ADR-0010**（金额表示：Ledger 启用 `Money` 值对象 vs 仅用 long 分）→ **Proposed**，建议「Ledger 内部启用 Money VO，跨服务全量激活另行排期（审计 P0-1）」。
- **ADR-0011**（MVP 记账范围：支付/退款/结算哪些首批）→ **Proposed**，建议「支付 + 退款首批，结算跟随本 Feature」。

实现前 MUST 由负责人确认上述 4 条 ADR（Constitution §8 人类决策边界：服务边界/数据所有权/资金表属重大架构变化）。
