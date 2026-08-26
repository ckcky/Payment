package com.payment.common.dto.rpc;

/**
 * 执行渠道退款的跨服务 RPC 请求（refund-service → payment-service）。
 *
 * <p>携带退款决策所需事实；payment-service 只执行渠道退款尝试并返回结果，
 * 不决定退款整体状态（退款决策归属 refund-service）。</p>
 */
public record RefundAttemptRequest(Long refundId, Long paymentId, String orderId, String userId,
                                   long amountMinor, String currencyCode, String reason,
                                   String idempotencyKey) {
}
