package com.payment.refund.api;

import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;

/**
 * 退款响应 DTO。状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 */
public record RefundResponse(Long id, Long paymentId, String orderId, long amountMinor,
                             long refundedAmountMinor, String currencyCode, String status,
                             String failureReason) {

    public static RefundResponse from(Refund refund) {
        RefundStatus status = refund.getStatus();
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getOrderId(),
                refund.getAmountMinor(),
                refund.getRefundedAmountMinor(),
                refund.getCurrencyCode(),
                status.name(),
                refund.getFailureReason());
    }
}
