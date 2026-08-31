package com.payment.catalog.api.dto;

/**
 * 预占请求（order-service → catalog-service）。字段名与调用方命令对象保持一致以便 JSON 映射。
 */
public record StockReserveRequest(String reservationId, Long skuId, long quantity) {
}
