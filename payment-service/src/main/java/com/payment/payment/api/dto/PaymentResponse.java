package com.payment.payment.api.dto;

import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;

/**
 * 支付响应 DTO。状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 */
public record PaymentResponse(Long id, String paymentNo, String transactionId, String orderId, String userId,
                              long amountMinor, String currencyCode, String status,
                              String failureReason) {

    public static PaymentResponse from(Payment payment) {
        PaymentStatus status = payment.getStatus();
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentNo(),
                payment.getTransactionId(),
                payment.getOrderId(),
                payment.getUserId(),
                payment.getAmountMinor(),
                payment.getCurrencyCode(),
                status.name(),
                payment.getFailureReason());
    }
}
