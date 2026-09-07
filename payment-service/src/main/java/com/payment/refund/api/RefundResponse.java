package com.payment.refund.api;

import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;

/**
 * 退款响应 DTO。状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 *
 * <p>spec 019（ADR-0067）：{@code transactionRefundNo} 为上层交易退款单（TXRF，双号互记）；
 * 存量手工退款为 {@code null}。</p>
 */
public record RefundResponse(Long id, String refundNo, String transactionRefundNo, String paymentNo,
                             String orderNo, long amountMinor, String currencyCode,
                             String status, String failureReason) {

    public static RefundResponse from(Refund refund) {
        RefundStatus status = refund.getStatus();
        return new RefundResponse(
                refund.getId(),
                refund.getRefundNo(),
                refund.getTransactionRefundNo(),
                refund.getPaymentNo(),
                refund.getOrderNo(),
                refund.getAmountMinor(),
                refund.getCurrencyCode(),
                status.name(),
                refund.getFailureReason());
    }
}
