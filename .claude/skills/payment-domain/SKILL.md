---
name: payment-domain
description: 支付领域知识——六条关键边界、资金正确性铁律、幂等与状态机、未知支付状态处理。凡涉及支付/退款/结算/账本/对账的资金路径必读。
---

# 支付领域知识

来源：`.specify/memory/constitution.md` §II、§V。资金正确性 > 一切。

## 领域清单

`Merchant` · `Product/SKU` · `Order` · `Payment` · `Channel` · `Refund` · `Fulfillment` · `Entitlement` · `Reconciliation` · `Settlement` · `Ledger`（资金账本）

## 资金正确性铁律（§2.2）

1. 金额一律用**最小货币单位（整数分）**。全库 MUST NOT 用 `float`/`double` 表示或计算金额。
2. 任何资金变动 MUST 通过 `Ledger` 复式记账，借贷平衡；MUST NOT 直接修改余额字段。
3. 资金路径（支付、退款、结算）MUST 具备幂等性。

## 六条关键边界（§2.3，必须时刻区分）

| # | 边界 | 含义 |
|---|---|---|
| 1 | **Order ≠ Payment** | Order 是商业意图，Payment 是资金动作，各自独立生命周期与状态机。订单金额 / 已支付金额 / 已退款金额是三个不同字段。 |
| 2 | **Payment ≠ Channel** | Payment 是编排层（意图、金额、状态、幂等），Channel 是外部支付方技术适配（协议、签名、回调）。Payment 只依赖 Channel 接口，不依赖具体实现。 |
| 3 | **Payment Success ≠ Entitlement Granted** | 支付成功是财务事件，权益授予是消费权利；不同生命周期。不得把「已支付」等价为「有权益」。 |
| 4 | **Reconciliation ≠ Settlement** | 对账是校验/审计（比对两本账找差异），结算是资金划转。二者解耦，周期不同（如 T+1）。 |
| 5 | **Refund ≠ Payment Refund** | Refund 是跨领域退款编排：渠道退款（资金）+ 权益撤销（权利）+ 账本冲正（会计）+ 对账调整（校验）。不得实现成「调渠道退款接口」一句话。 |
| 6 | **Fulfillment 不强耦合 Payment** | 履约有自己的状态机，可被支付触发但不依赖支付内部实现，不反向阻塞。通过事件/编排解耦。 |

## 领域依赖方向（§2.4）

单向向内：编排层（Order/Payment/Refund）可依赖底层领域；底层领域 MUST NOT 反向依赖编排层。`Ledger` 是被依赖方，不依赖任何业务领域。`Channel` 只依赖外部协议。

## 一致性必答题（§4）

1. **幂等**：资金入口 MUST 有幂等键，服务端持久化 + 唯一约束，重复请求不产生重复资金动作。
2. **状态机**：Order/Payment/Refund/Fulfillment/Entitlement/Settlement 都 MUST 有显式单向状态机；流转集中在 domain 状态转换函数。
3. **最终一致**：与外部系统（渠道/网关）交互用最终一致；服务内部用本地事务；跨服务通过同步 RPC、幂等重试和状态查询收敛。三者分层。
4. **重试**：仅对幂等外部调用允许自动重试，带 backoff 与上限。
5. **重复 RPC/回调**：处理侧 MUST 假设重复，靠幂等键 + 状态机吸收。
6. **超时**：外部调用 MUST 有 context 超时；超时 ≠ 失败或成功。
7. **未知支付状态（最核心）**：结果不确定时 MUST NOT 猜成败直接落账，进 UNKNOWN/PENDING，靠查询接口/对账/人工收敛。


## 开发前必须：

1. 阅读支付领域文档
2. 阅读退款领域文档
3. 查看状态机
4. 搜索现有幂等实现
5. 搜索现有 RPC 调用模式


## 必须考虑：

- 幂等
- 状态转换
- Timeout
- Retry
- Duplicate Callback
- Unknown State
- RPC 超时和最终一致

## 禁止：

Payment 直接修改 Entitlement 内部状态。

## 优先使用：

Payment Success
→ Fulfillment RPC
→ Entitlement RPC