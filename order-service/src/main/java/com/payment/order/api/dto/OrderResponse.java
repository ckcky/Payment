package com.payment.order.api.dto;

import com.payment.order.domain.Order;
import java.util.List;

/**
 * 订单详情响应。
 */
public record OrderResponse(Long id, String orderNo, String userId, String merchantId, String status,
                            long totalMinor, String currencyCode, long paidMinor, long refundedMinor,
                            List<OrderItemResponse> items) {

    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(OrderItemResponse::from).toList();
        return new OrderResponse(order.getId(), order.getOrderNo(), order.getUserId(), order.getMerchantId(),
                order.getStatus().name(), order.getTotalMinor(), order.getCurrencyCode(),
                order.getPaidMinor(), order.getRefundedMinor(), items);
    }
}
