package com.payment.order.api.dto;

import com.payment.order.application.CreateOrderResult;

/**
 * 创建订单响应：订单 + 其 1:1 交易。
 */
public record CreateOrderResponse(Long orderId, Long transactionId, String status,
                                  long totalMinor, String currencyCode) {

    public static CreateOrderResponse from(CreateOrderResult result) {
        return new CreateOrderResponse(result.orderId(), result.transactionId(),
                result.status().name(), result.totalMinor(), result.currencyCode());
    }
}
