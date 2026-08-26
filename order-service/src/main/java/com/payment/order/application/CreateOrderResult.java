package com.payment.order.application;

import com.payment.order.domain.OrderStatus;

/**
 * 创建订单结果：订单 + 其 1:1 交易。
 */
public record CreateOrderResult(Long orderId, Long transactionId, OrderStatus status,
                                long totalMinor, String currencyCode) {
}
