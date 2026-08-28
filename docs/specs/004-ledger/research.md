# Research: Ledger 设计决策汇总（Phase 0）

**Feature**: `004-ledger` | **Date**: 2026-08-28 | **Plan**: [plan.md](plan.md)

> 本文件汇总 Ledger 的设计分歧点，正式决策见 `docs/adr/0004-ledger-design-decisions.md`（ADR-0008~0011，状态 Proposed，待负责人确认）。

## 决策 1：数据模型 —— 复式记账 + 科目/分录结构（ADR-0008）

- **问题**：账本如何建模才能满足 Constitution §II.3「复式记账、借贷平衡、可追溯」？
- **推荐**：`Account` + `Posting`（聚合根，持幂等键/来源）+ `LedgerEntry`（不可变分录）。每笔记账 = 一个平衡 `Posting`（≥2 条借贷相等分录）。
- **备选**：
  - A. 单式余额表（只记余额变动）：最简单，但无法满足「复式」「可追溯」「借贷平衡校验」，违反 Constitution（否决）。
  - B. 事件溯源（Event Sourcing）：强大但超出当前架构（Constitution §4 禁止为炫技引入复杂仪式），MVP 不采用（否决，留待 Phase 10 评估）。
  - C. 复式记账 + append-only 分录（采纳）。

## 决策 2：记账触发与一致性（ADR-0009）

- **问题**：支付成功后何时、如何把事实写入账本？账本失败是否回滚业务？
- **推荐**：支付/退款/结算在「已确认」状态后，通过**同步 RPC（OpenFeign）**调用 `ledger-service.postEntries`（幂等键唯一约束）。账本失败 **MUST NOT** 回滚业务事实（Saga）；调用方 `LedgerPostingGateway` 做有限退避重试，耗尽后入「待记账」清单由 reconciliation 补齐。
- **备选**：
  - A. 业务库与账本库 2PC/XA：违反 Constitution §4 禁止清单（否决）。
  - B. MQ 异步事件驱动记账：当前架构无 MQ（ADR-0001），且引入运维负担（否决，留待 Phase 10）。
  - C. 同步 RPC + 幂等 + 重试/对账兜底（采纳，与 ADR-0001/§V 一致）。

## 决策 3：金额表示 —— 启用 Money 值对象 vs 仅 long 分（ADR-0010）

- **问题**：账本内部金额用 `Money` 值对象（common-core，当前死代码）还是 bare `long` 分？
- **推荐**：Ledger 内部启用 `Money` 值对象（激活审计 P0-1 中提到的死代码 Money VO），在 ledger 边界做 long↔Money 转换；跨服务全量 Money 激活（payment/order/refund...）另行排期（不阻塞本 Feature）。
- **备选**：
  - A. 全仓立即改用 Money：正确性最佳，但触碰 ~50 处（审计 P0-1），风险与范围过大（否决，单独排期）。
  - B. Ledger 仅用 long 分：改动最小，但错过激活 Money VO 的机会，且账本作为「资金正确性核心」应率先示范（不优先）。
  - C. Ledger 启用 Money、其余沿用 long 分（采纳，渐进式）。

## 决策 4：MVP 记账范围（ADR-0011）

- **问题**：首批覆盖哪些资金变动的记账？
- **推荐**：**支付成功 + 退款** 首批（直接资金流），**结算**跟随本 Feature（Net 结转）。三者均属 Phase 8 既定范围。
- **备选**：
  - A. 仅支付成功：范围过小，退款/结算仍无账本覆盖（否决）。
  - B. 支付+退款+结算+通道清算：范围过大，通道清算属 Phase 后续（否决）。
  - C. 支付+退款首批、结算跟随（采纳）。

## 待负责人确认清单（Constitution §8）

| ADR | 主题 | 推荐状态 | 关联人类决策边界 |
|---|---|---|---|
| ADR-0008 | 复式记账数据模型 | Accepted | §8.2 新增服务/数据所有权 |
| ADR-0009 | 记账触发与一致性 | Accepted | §8.2 / §8.3 |
| ADR-0010 | 金额表示（Money VO） | Accepted（渐进） | §8.8 领域模型 |
| ADR-0011 | MVP 记账范围 | Accepted | §8.2 |
