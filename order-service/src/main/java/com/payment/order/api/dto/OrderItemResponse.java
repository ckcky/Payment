package com.payment.order.api.dto;

import com.payment.order.domain.OrderItem;

/**
 * 订单明细响应。
 */
public record OrderItemResponse(String skuId, String skuCode, String name, int quantity,
                                long priceMinor, String currencyCode) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getSkuId(), item.getSkuCode(), item.getName(),
                item.getQuantity(), item.getPriceMinor(), item.getCurrencyCode());
    }
}
