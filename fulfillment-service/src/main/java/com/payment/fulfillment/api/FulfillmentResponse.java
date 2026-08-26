package com.payment.fulfillment.api;

import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentStatus;

/**
 * 履约查询响应 DTO。
 */
public record FulfillmentResponse(
        Long id,
        String orderId,
        String sourcePaymentId,
        FulfillmentStatus status,
        String failureReason) {

    public static FulfillmentResponse from(Fulfillment fulfillment) {
        return new FulfillmentResponse(
                fulfillment.getId(),
                fulfillment.getOrderId(),
                fulfillment.getSourcePaymentId(),
                fulfillment.getStatus(),
                fulfillment.getFailureReason());
    }
}
