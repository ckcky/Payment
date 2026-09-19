# MVP 契约

本目录定义模块边界上的业务契约，不绑定具体传输协议。实现阶段可将其映射为 HTTP 请求/响应、模块调用或事件载荷。

## 命令与查询

### 创建订单

- 输入：用户引用、商户引用、SKU 选择、数量和购买上下文。
- 输出：订单身份、不可变的价格/明细快照、订单状态、总金额和币种。
- 拒绝条件：SKU 不可销售、数量无效或商户不具备销售资格。

### 创建支付意图

- 输入：订单身份、交易身份、支付金额/币种、客户端幂等键和所选渠道。
- 输出：支付身份、初始状态、已创建时的支付尝试引用和渠道交互指引。
- 拒绝条件：订单不可支付、金额/币种不一致，或幂等键与另一请求冲突。

### 渠道回调

- 输入：渠道身份、渠道引用、回调事件身份、渠道结果、结果时间和签名来源上下文。
- 输出：已接受或已忽略的当前 Payment 状态。
- 规则：重复和延迟回调必须安全；权威成功不能被冲突的迟到失败覆盖；不确定内容保持 UNKNOWN。

### 收敛未知支付

- 输入：Payment/Attempt 身份或渠道引用，以及权威查询/回调结果。
- 输出：当前 Payment 和 Attempt 状态。
- 规则：只有权威结果可以收敛 UNKNOWN；收敛过程最多发布一次 PaymentSucceeded 或 PaymentFailed。

### 申请退款

- 输入：订单/支付身份、申请金额、币种、原因和退款幂等键。
- 输出：退款身份和当前退款状态。
- 拒绝条件：金额超过可退款金额、币种不一致或原支付不具备退款资格。

### 执行对账

- 输入：商户和对账周期、平台已确认事实、外部渠道事实。
- 输出：对账批次、匹配结果和差异记录。
- 规则：永远不修改原始 Payment 或 Refund 事实。

### 创建结算批次

- 输入：商户、结算周期、已确认的合格事实集合和调整项。
- 输出：结算批次身份、应结金额和状态。
- 拒绝条件：事实未确认、重大差异未解决，或商户-周期批次已存在。

## RPC 结果与服务内部事实

所有跨服务 RPC 都携带请求身份、关联 ID、来源服务和契约版本；服务内部事实可以记录状态变化，但不跨服务发布。

- `PaymentSucceeded`：Payment 和 Transaction 已确认成功。
- `PaymentFailed`：Payment 已确认失败。
- `PaymentUnknown`：Payment 结果尚未获得权威确认。
- `FulfillmentRequested`：可以开始处理所引用订单/明细的履约。
- `FulfillmentCompleted` / `FulfillmentFailed`：履约结果。
- `EntitlementGrantRequested` / `EntitlementGranted` / `EntitlementGrantFailed`：消费权利授予结果。
- `RefundSucceeded` / `RefundUnknown` / `RefundFailed`：退款结果。
- `ReconciliationCompleted` / `ReconciliationDifferenceFound`：对账结果。
- `SettlementCreated` / `SettlementSucceeded` / `SettlementUnknown` / `SettlementFailed`：结算生命周期结果。

## RPC 契约规则

- RPC 是业务命令或查询，绝不是直接写入其他服务内部数据的权限。
- 调用方和被调用方必须幂等，并能处理重复请求、延迟、超时和重试。
- 所有包含金额的契约都必须明确金额和币种。
- UNKNOWN 必须显式表达，不能为了方便转换成失败。
