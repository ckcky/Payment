package com.payment.order.api.dto;

import java.util.List;

/**
 * 创建订单请求。
 */
public record CreateOrderRequest(String userId, String merchantId, List<OrderLineRequest> items) {
}
