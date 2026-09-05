package com.payment.common.dto.rpc;

/**
 * 退款 → 履约的撤销请求（payment-service（refund 包）→ fulfillment-service）。
 *
 * <p>退款确认后请求下游撤销对应履约；下游按自身状态机决定动作（PENDING 可取消，
 * 其余状态不可逆），退款侧不假设结果。</p>
 */
public record RefundFulfillmentRequest(String refundNo, String paymentNo, String orderNo,
                                       String userId, String reason) {
}
