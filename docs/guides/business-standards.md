# 业务流程规范（Business Standards）

> 把 Constitution 中与**业务 / 领域**相关的原则操作化为可执行的检查清单，供实现与评审逐条对照。
>
> **权威层级**：SSOT 仍是 [Constitution](../../.specify/memory/constitution.md)；本文件是其展开，**不得与宪法冲突**。流程规范见 [ai-standards.md](ai-standards.md)，技术规范见 [engineering-standards.md](engineering-standards.md)。

## 1. 领域边界（六条铁律，Constitution §III）

| # | 区分 | 含义 |
|---|---|---|
| 1 | Order ≠ Payment | 订单是商业意图，支付是资金动作，独立生命周期与状态机 |
| 2 | Payment ≠ Channel | 支付是编排层，渠道是技术适配；支付只依赖接口抽象 |
| 3 | Payment Success ≠ Entitlement Granted | 支付成功是财务事件，权益是消费权利，成功只「触发」授予 |
| 4 | Reconciliation ≠ Settlement | 对账是比对找差异，结算是资金划转，二者解耦 |
| 5 | Refund ≠ Payment Refund | 退款是跨多领域编排，不是「调一次渠道退款」 |
| 6 | Fulfillment 不强耦合 Payment | 履约有自己的状态机，不被支付状态反向阻塞 |

## 2. 依赖方向与数据所有权

- **单向、向内**：编排层（Order/Payment/Refund）可依赖底层领域；底层领域**不得**反向依赖编排层；`Ledger` 只被依赖；`Channel` 只依赖外部协议。
- **Database-per-Service**：每服务独占自己的 Schema；跨服务读写一律经对方公开 API/RPC。
- **禁止**任何服务直接 SQL 他服务 Schema 的表。
- **账本唯一事实源**：所有真实资金变动经 `ledger-service`（Constitution §II / ADR-0008）。

## 3. 资金正确性铁律（最高优先级，Constitution §II）

- 金额用最小货币单位（`long` 分）+ 独立 `currencyCode` 字段，或 `BigDecimal`（明确 scale）；**禁止 `float` / `double`**。
- **幂等**：支付 / 退款 / 结算等资金入口 MUST 有幂等键，数据库唯一约束兜底；同键重复请求返回同一业务结果，不产生重复资金动作。
- **复式记账**：任何真实资金变动经 ledger，借贷平衡；分录不可变（更正只新增反向分录）。
- **UNKNOWN 不猜成败**：结果不确定时进 UNKNOWN，靠主动查询 / 对账 / 人工收敛；**禁止**猜成败直接落账。

## 4. 状态机纪律

- Order / Payment / Refund / Fulfillment / Entitlement / Settlement 均为**显式、单向**状态机。
- 状态流转集中在 `domain` 的状态转换函数，**禁止**散落直接 `set` 状态绕过状态机。

## 5. 一致性模型（Constitution §V）

- 跨服务用 **Saga + 同步 RPC + 幂等重试**实现最终一致；**禁止 2PC/XA**。
- 每段失败**不回滚**前序已成功事实，靠幂等重试 / 对账 / 人工收敛补齐。
- 后置 RPC 失败**不得**回写前序成功事实（如履约失败不回写支付为失败）。
- 金额 / 币种 / 累计金额校验在支付、退款、对账、结算**各边界分别执行**，不依赖上游校验。

## 6. 评审必答清单（触及资金路径时逐条对照）

- [ ] 幂等键是否具备？重复请求是否返回同一结果、不产生重复资金动作？
- [ ] 资金变动是否经 ledger 复式记账？借贷是否平衡？
- [ ] 状态流转是否走集中状态机，无散落 `set`？
- [ ] 未知结果是否落 UNKNOWN，而非猜成败直接落账？
- [ ] 跨服务依赖方向是否单向向内？是否直接 SQL 了他服务表？
- [ ] 一致性是否用 Saga + 幂等，未引入 2PC/XA？

> `/payment-review` 命令的完整检查项见 `.claude/commands/payment-review.md`；资金路径的工程侧约束见 [engineering-standards.md](engineering-standards.md) §2/§3。
