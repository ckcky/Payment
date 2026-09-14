<a id="adr-0063"></a>

# ADR-0063: 跨系统关联一律使用业务单号，数值主键不跨服务

- 状态：✅ Accepted（2026-09-04）
- 关联：ADR-0062（两字母前缀 + 雪花业务单号）、spec 015
- 负责人裁决：负责人要求「不要用 orderId 关联，改用 orderNo；payment_attempts 用 paymentNo；接口调用请求里也不要用这两个数值 ID」

## 背景

ADR-0062 为七类单据引入业务单号（OR/TX/PM/RF/SB/RB/LP + 雪花），但跨系统关联列与
API 请求仍传数值自增主键（orders.id / payments.id）。数值 ID 出服务有两个问题：
1. 暴露业务量级（可被遍历），且分库分表/迁移时不稳定；
2. 语义混乱：同一笔订单在不同系统叫法不同（order_id 存的其实是 orders.id 的字符串），排障与演示页解释成本高。

## 决策

1. **跨系统关联列一律存业务单号**（VARCHAR(32)，引用方不再落数值 ID）：
   - `order.order_items.order_no`、`order.transactions.order_no`（原 order_id）；
   - `payment.payments.order_no`（原 order_id）；`payment.payment_attempts.payment_no`（原 payment_id BIGINT）；
   - `refund.refunds.order_no / payment_no`；`refund.refund_intake_locks.payment_no`（排他锁行键同步改单号）；
   - `fulfillment.fulfillments.order_no / source_payment_no`；`entitlement.entitlements.order_no`。
2. **接口请求/响应与 URL 路径一律用单号**：
   - CreatePaymentRequest(orderNo…)、CreatePaymentResponse(paymentNo…)、PaymentSucceededRequest(paymentNo, orderNo…)、
     PaymentAmountQueryRequest/Response(paymentNo, orderNo…)、RefundAttempt/RefundPostProcess/RefundFulfillment(paymentNo, orderNo…)、
     FulfillmentCompletedRequest(orderNo…)；
   - 渠道回调链：收银台 URL 携带 paymentNo/orderNo，`POST /internal/payments/{paymentNo}/channel-callback`；
   - 查询接口：GET /orders/{ref}、GET /payments/{ref}、POST /payments/{ref}/resolve 支持「数值 id 或单号」双轨寻址
     （数值仅为历史兼容灰度，新调用一律传单号）；履约/权益 by-order/{orderNo}。
3. **数值主键保留**：各表自增 id（含 payments.id / refunds.id）仍是本服务聚合内部标识
   （乐观锁、attempt 引用、账本 postings.source_id 内部口径），不出服务边界。
4. **2026-09-05 收口补充**（本轮裁决覆盖第 2 条的「双轨寻址」残留与出站请求遗留）：
   - 渠道出站请求 `ChargeRequest(paymentNo…)` / `QueryStatusRequest(paymentNo…)` 去数值 paymentId；
   - 对账事实 RPC `PaymentFactResponse/RefundFactResponse` 及 reconciliation 侧镜像 DTO
     改为 `paymentNo` / `refundNo`，不再暴露数值 id；
   - 记账 RPC `PostingRequest.sourceId` 传 paymentNo（原数值 paymentId），
     `LedgerPostingGateway.postPaymentCapture` 参数同步改 `String paymentNo`；
   - 库存预占/确认/释放幂等键 `order:{orderNo}:sku:{skuId}`（原 orderId 拼接）；
   - 退款域并入 payment-service（ADR-0064）后，reconciliation 的
     `RefundFactsFeignClient` 服务名由 refund-service 改指 payment-service（8085 退役）。

## 后果

- 落库记录自描述：任何一行的单号即全局可读标识，跨系统排障/演示直查直达；
- 数值 ID 不再暴露业务量级；跨库迁移可保持单号稳定；
- 旧数据不迁移（演示环境重建库）；幂等键 `payment:{orderNo}:{channelCode}:{attemptSeq}` 随之切换；
- 对账/结算链路不受影响（其引用本就是渠道流水号）。

## 验证

- 六模块（order/payment/refund/fulfillment/entitlement/mock-channel-web）单测全绿；
- 端到端：下单 → 收银台（paymentNo 单号 URL）→ 回调 → 履约/权益/记账 → 退款 → 结算/对账，
  演示页全链路 DB 数据卡片按单号关联展示全部落库记录。
