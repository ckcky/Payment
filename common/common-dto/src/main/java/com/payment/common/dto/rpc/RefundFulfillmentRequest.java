package com.payment.common.dto.rpc;

/**
 * 退款 → 履约的撤销请求（refund-service → fulfillment-service）。
 *
 * <p>退款确认后请求下游撤销对应履约；下游按自身状态机决定动作（PENDING 可取消，
 * 其余状态不可逆），refund-service 不假设结果。</p>
 */
public record RefundFulfillmentRequest(Long refundId, String paymentNo, String orderNo,
                                       String userId, String reason) {
}
