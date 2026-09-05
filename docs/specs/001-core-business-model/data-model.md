# 数据模型：Commerce & Payment Platform MVP

> 本文描述业务实体、关系、约束和状态，不是数据库表设计。具体字段类型、表结构和迁移属于后续实现任务。

## 聚合与所有权概览

| 模块 | 聚合根/事实 | 关键关系 |
|---|---|---|
| Merchant | Merchant | Merchant 关联 Product、Order、Settlement |
| Catalog | Product、SKU | Product 组织 SKU；SKU 提供可购买和交付定义 |
| Order | Order | Order 包含 Order Items 和 Price Snapshots |
| Transaction | Transaction | MVP 中与 Order 1:1，表达支付义务 |
| Payment | Payment | MVP 中与 Transaction **1:N**（一交易多支付单，ADR-0064：用户每选一个支付方式即新建一张支付单）；包含 Payment Attempts |
| Payment | PaymentAttempt | 一个 Payment 对应**一条**尝试记录（`payment_no : payment_attempts = 1:1`，ADR-0054 口径；代码证据 `PaymentPersistence.java:56` 每支付单仅建一条 attempt）；渠道重试在同一 attempt 行内以 `retry_count` 递增，**不新建行**；每条尝试最多对应一个渠道引用 |
| Fulfillment | Fulfillment | 由 PaymentSucceeded 触发，引用 Order/Order Item |
| Entitlement | Entitlement | 由履约或其他合法来源授予，关联用户和交付内容 |
| Refund | Refund | 引用原 Order、Payment，可包含多个 Refund Items |
| Reconciliation | Reconciliation Batch、Difference | 比对已确认 Payment/Refund 事实与外部记录 |
| Settlement | Settlement Batch、Settlement Item、Adjustment | 基于已确认财务事实生成商户批次 |

## 实体定义

### Merchant

- 身份信息、经营状态、结算资格、结算账户引用。
- 约束：只有具备结算资格的商户才能进入结算计算。

### Product and SKU

- Product：商品身份、类型、生命周期状态、展示/版本引用。
- SKU：商品引用、可销售属性、价格引用、交付定义、可销售状态。
- 约束：只有可销售的 SKU 才能加入新订单。

### Order and Order Item

- Order：用户引用、商户引用、状态、订单总额、币种、已支付金额、已退款金额。
- Order Item：SKU 引用、数量、价格快照、交付定义快照。
- 约束：订单总额等于明细金额之和；已支付金额和已退款金额独立追踪；订单快照创建后不可修改。

### Transaction

- Transaction 身份、订单引用、金额、币种、用途、状态。
- MVP 约束：一个 Order 只有一个有效 Transaction；Transaction 表示订单的支付义务，不替代 Order 或 Payment。

### Payment

- Payment 身份、Transaction 引用、支付意图、金额、币种、幂等键、状态、当前/关联尝试引用。
- 约束：一个 Transaction 可对应**多个 Payment**（一交易多支付单，ADR-0064：`transaction_no : payment_no = 1:N`，去掉 `uk_payments_transaction_id` 唯一约束、以 `attempt_seq` 区分）；金额和币种必须与 Transaction 一致；终态成功不能被后到的失败回调覆盖。

### PaymentAttempt

- Attempt 身份、Payment 引用、渠道身份、渠道引用、请求时间、响应时间、结果、状态、重试信息。
- 约束：每次尝试都必须可独立追踪；重复回调映射到同一个渠道引用；未知尝试在获得权威结果前保持未收敛。

### Fulfillment and Entitlement

- Fulfillment：履约身份、订单/明细引用、交付内容、状态和失败原因。
- Entitlement：权益身份、用户、来源、授予引用、可用范围/数量、有效期和状态。
- 约束：Payment 成功可以请求履约，但不能直接创建可用权益。

### Refund

- Refund：退款身份、订单/支付引用、金额、币种、原因、幂等键、状态。
- Refund Item：订单明细引用、申请金额、履约/权益处理引用。
- 约束：累计成功或处理中的退款金额不能超过可退款金额；未知退款在没有收敛规则前不能再次提交。

### Reconciliation

- Batch：周期、数据来源、运行状态、完成时间。
- Match：平台事实引用、外部事实引用、匹配结果。
- Difference：差异类型、金额/币种差额、来源记录、处理状态和解决依据。
- 约束：对账永远不修改原始 Payment/Refund 事实。

### Settlement

- Batch：商户、周期、已确认的来源事实集合、收入总额、退款金额、调整项、应结金额、状态。
- Item：来源 Payment/Refund 引用及其金额贡献。
- Adjustment：原因、金额、审批/引用和影响。
- 约束：MVP 中一个商户周期只有一个批次；未确认或重大未解决事实不能纳入；未知执行结果不等于成功。

## 状态机

状态转换规则由 Feature Spec 定义，并且必须作为领域规则实现：

- Order: pending confirmation → pending payment → paid → fulfilling → completed; cancellation/closure only when allowed.
- Transaction：待处理 → 处理中 → 成功/失败/已取消/未知。
- Payment：待支付 → 处理中 → 成功/失败/未知 → 已关闭。
- PaymentAttempt：待处理 → 已受理 → 成功/失败/未知。
- Fulfillment：待履约 → 处理中 → 已交付/部分交付/失败/已取消。
- Entitlement：待授予 → 可用 → 部分使用/已用尽 → 已过期/已撤销/失败。
- Refund：申请中 → 处理中 → 成功/部分成功/失败/未知/已拒绝/已关闭。
- Reconciliation：待处理 → 对账中 → 一致/有差异 → 处理中/已关闭。
- Settlement：待结算 → 计算中 → 待执行 → 执行中 → 成功/失败/未知/已关闭。

## 跨实体约束

1. Payment 只有在 Transaction 及金额/币种关系有效时才能成功。
2. PaymentSucceeded 不会直接设置 Fulfillment 或 Entitlement 状态。
3. Refund 金额受已确认支付金额减去此前已占用可退款金额的结果约束。
4. Settlement 只消费已确认事实，并且始终可追溯到来源 Payment/Refund 记录。
5. 重复命令或 RPC 请求不能创建重复的 PaymentAttempt、Fulfillment、Entitlement 授予或 Settlement 批次。
