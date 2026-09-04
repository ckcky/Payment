package com.payment.fulfillment.api;

import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentStatus;

/**
 * 履约查询响应 DTO。
 */
public record FulfillmentResponse(
        Long id,
        String orderNo,
        String sourcePaymentNo,
        FulfillmentStatus status,
        String failureReason) {

    public static FulfillmentResponse from(Fulfillment fulfillment) {
        return new FulfillmentResponse(
                fulfillment.getId(),
                fulfillment.getOrderNo(),
                fulfillment.getSourcePaymentNo(),
                fulfillment.getStatus(),
                fulfillment.getFailureReason());
    }
}
