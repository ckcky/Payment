package com.payment.order.api.dto;

/**
 * 下单行请求。
 */
public record OrderLineRequest(Long skuId, int quantity) {
}
