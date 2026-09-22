---
name: payment-domain
description: 支付领域知识导航——关键边界、资金正确性铁律、幂等与状态机、未知状态处理。凡涉及支付/退款/结算/账本/对账的资金路径必读。事实以 Constitution 与 L0 文档为准。
---

# 支付领域知识（导航层）

> **本技能不定义领域事实**。领域边界与规则的权威来源：
>
> | 需要的事实 | 权威来源 |
> |---|---|
> | 资金正确性铁律 | [.specify/memory/constitution.md](../../../.specify/memory/constitution.md) §II |
> | 领域边界 / 依赖方向 | 同上 §III |
> | 一致性口径 | 同上 §V |
> | 业务流程规范 | [docs/guides/business-standards.md](../../../docs/guides/business-standards.md) |
> | 某服务的数据模型 / 状态机 / 契约 | [docs/architecture/systems/](../../../docs/architecture/systems/) 对应文档（15 章骨架） |
> | 跨域链路与时序 | [docs/architecture/diagrams/](../../../docs/architecture/diagrams/)（04 支付流 / 05 未知恢复 / 06 退款流 / 07 记账对账结算） |

资金正确性 > 一切。

## 领域清单

`Merchant` · `Product/SKU` · `Order` · `Payment` · `Channel` · `Refund` · `Fulfillment` · `Entitlement` · `Reconciliation` · `Settlement` · `Ledger`（资金账本）

> ⚠️ **`Refund` 不是独立服务**：退款域已并入 **payment-service**（ADR-0064），退款**发起方是 order 层**（ADR-0067）；旧文档中的 `refund-service` 属历史。

## 资金正确性铁律（Constitution §II）

1. 金额一律用**最小货币单位（整数分）**（`*Minor`，`long`）。全库 MUST NOT 用 `float`/`double` 表示或计算金额。
2. 任何资金变动 MUST 通过 `Ledger` 复式记账，借贷平衡；MUST NOT 直接修改余额字段。
3. 资金路径（支付、退款、结算）MUST 具备幂等性。
4. **未知状态不猜成败**：结果不确定时进 `UNKNOWN`，靠主动查询 / 回调 / 对账收敛。

## 关键边界（Constitution §III，必须时刻区分）

| # | 边界 | 含义 |
|---|---|---|
| 1 | **Order ≠ Payment** | Order 是商业意图，Payment 是资金动作，各自独立生命周期与状态机。订单金额 / 已支付金额 / 已退款金额是三个不同字段。 |
| 2 | **Payment ≠ Channel** | Payment 是编排层（意图、金额、状态、幂等），Channel 是外部支付方技术适配（协议、签名、回调）。payment-service 内二者以**两层结构**分离（ADR-0072）：`payments` 只由支付层写、`payment_attempts` 只由渠道层写；支付层只依赖 `PaymentChannel` 端口，不依赖具体渠道实现。 |
| 3 | **Payment Success ≠ Entitlement Granted** | 支付成功是财务事件，权益授予是消费权利；不同生命周期。不得把「已支付」等价为「有权益」。 |
| 4 | **Reconciliation ≠ Settlement** | 对账是校验/审计（比对两本账找差异），结算是资金划转。二者解耦，周期不同。 |
| 5 | **Refund 是跨域编排，不是「调渠道接口」** | 退款由 **order 层驱动**（ADR-0067：TXRF / PMRF 双层退款单）；payment-service 执行渠道退款与记账；权益撤销由 payment-service 退款域经 RPC 请求 entitlement-service。不得实现成「调渠道退款接口」一句话。 |
| 6 | **Fulfillment 不强耦合 Payment** | 支付成功后 payment-service **只通知 order-service**（业务侧唯一扇出，ADR-0054）；由 order 层编排 markPaid + confirmStock 并请求 fulfillment 履约，履约完成后再请求 entitlement 授予权益（`fulfillment → entitlement` 链保留）。履约失败不回写支付为失败。 |

## 领域依赖方向（Constitution §III）

单向向内：编排层（Order / Payment）可依赖底层领域；底层领域 MUST NOT 反向依赖编排层。`Ledger` 是被依赖方，不依赖任何业务领域。`Channel` 只依赖外部协议。反向路径（主动查询 / 重试 / 超时扫描 / 退款）的渠道来源恒为 `payment_attempts.channel_code`，**禁止重新路由**。

## 一致性必答题（Constitution §V）

1. **幂等**：资金入口 MUST 有幂等键（DB 唯一约束兜底）；下单入口为 Redis 防重（`Idempotency-Key`，四态：201 / 200 回放 / 409+Retry-After / fail-open，ADR-0039/0040）。
2. **状态机**：Order / Payment / Refund / Fulfillment / Entitlement / Settlement 都 MUST 有显式状态机；流转集中在 domain 状态转换函数，禁止散落直改 `status`。
3. **最终一致**：与外部系统（渠道）交互用最终一致；服务内部用本地事务；跨服务通过同步 RPC + 幂等 + 调用方补偿台账，异步通知走 **Redis 事务消息通道**（ADR-0074）。**禁止 2PC/XA**。
4. **重试**：仅对幂等外部调用允许自动重试，带 backoff 与上限。
5. **重复 RPC / 回调**：处理侧 MUST 假设重复，靠幂等键 + 状态机吸收。
6. **超时**：外部调用 MUST 有超时；超时 ≠ 失败或成功。
7. **未知支付状态（最核心）**：结果不确定时 MUST NOT 猜成败直接落账，进 UNKNOWN/PENDING，靠查询接口 / 对账 / 人工收敛。

## 开发前必须

1. 读 [business-standards.md](../../../docs/guides/business-standards.md) 与 Constitution §II/§III/§V；
2. 读相关服务的 System Design（数据模型 §4 / 状态机 §5 / 契约 §6 / 链路 §7）；
3. 看跨域时序图（`docs/architecture/diagrams/04~07`）；
4. 搜索现有幂等实现与 RPC 调用模式，**不要新造一套**。

## 必须考虑

幂等 · 状态转换 · Timeout · Retry · Duplicate Callback · Unknown State · RPC 超时与最终一致 · 重复支付（surplus）处置。

## 禁止

- payment 直接修改 entitlement / order 内部状态。
- 换渠道前「先关闭旧 Payment」或「禁止多个 Payment SUCCESS」——**重复支付自动退款是既有能力**（surplusRefund），不是缺口。
- 反向路径重新选路（退款换渠道 = 钱退错地方）。

## 正确的扇出链

```
Payment Success → 通知 order-service（唯一业务扇出）
              → order 层：markPaid + transaction.succeed() + confirmStock
              → order 驱动 fulfillment → fulfillment 完成后请求 entitlement 授予权益
```
