# MVP 快速验证指南

> 本文是实现完成后的验证指南。当前仓库尚未有可运行代码，因此命令和实际端口由实现任务补充；本指南不包含实现代码或数据库结构。

## 验证目标

证明一个用户能够完成购买，并验证 UNKNOWN、重复请求、履约/权益、退款、对账和结算主路径。

## 前置条件

- 启动本地 MVP 应用和其最小运行依赖。
- 初始化一个有效 Merchant、一个可售 Product/SKU、一个可交付商品和一个用户。
- 使用 Mock Payment Channel。
- 准备唯一的订单、支付和退款幂等键。

## 场景 1：成功购买

1. 创建或选择可售 SKU。
2. 创建 Order，确认 Order Item 和价格快照被冻结。
3. 创建 1:1 Transaction 和 1:1 Payment。
4. 发起 PaymentAttempt，确认使用 Payment 内部的 Channel 抽象。
5. 让 Mock Channel 返回成功回调。
6. 验证 PaymentSucceeded 只触发一次履约 RPC。
7. 验证 Fulfillment 异步进入完成状态。
8. 验证 Entitlement 在履约结果后进入可用状态。

预期：Order、Transaction、Payment、Fulfillment、Entitlement 各自状态正确，无重复实体或动作。

## 场景 2：UNKNOWN 收敛

1. 发起新的支付尝试。
2. 让 Mock Channel 模拟超时或不完整响应。
3. 验证 Payment/Attempt 进入 UNKNOWN。
4. 重复提交相同回调，确认不产生重复动作。
5. 使用查询或权威回调返回成功，验证只触发一次履约 RPC。
6. 重复执行查询，确认状态保持幂等。

预期：UNKNOWN 不被当作失败；收敛后才触发履约。

## 场景 3：退款

1. 对已支付订单请求部分退款。
2. 验证退款金额不超过可退款金额。
3. 模拟退款成功，确认 RefundSucceeded 和后续履约/权益处理事实。
4. 重复提交相同退款幂等键，确认返回原 Refund。
5. 模拟退款未知，确认不自动产生无法确认的第二次资金动作。

预期：部分退款、重复退款和未知退款均可追踪。

## 场景 4：对账与结算

1. 准备平台已确认 Payment/Refund facts 和对应渠道 records。
2. 执行基础对账。
3. 验证匹配、金额差异、状态差异、平台独有和渠道独有记录均可识别。
4. 确认对账不修改原始 Payment/Refund 状态。
5. 对已确认且无重大未处理差异的数据创建 Merchant-period Settlement Batch。
6. 重复创建同一批次，确认幂等；模拟未知执行，确认不重复结算。

预期：Reconciliation 与 Settlement 独立，结算只使用满足资格的数据。

## 场景 5：可观测性

验证每条场景都能通过关联 ID追踪 Order、Transaction、Payment、Attempt、Refund、Fulfillment、Entitlement、Reconciliation 和 Settlement；并能观察支付成功/失败/UNKNOWN、重复回调、履约失败、权益失败、对账差异和结算结果。

## 退出条件

- 三个用户故事的独立测试全部通过。
- 所有状态转换和幂等断言通过。
- UNKNOWN 场景有明确收敛结果。
- 没有真实资金划转；Ledger 未实现的限制已在运行说明中明确。
