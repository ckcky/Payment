package com.payment.catalog.api.dto;

/**
 * 释放请求（order-service → catalog-service）。
 */
public record StockReleaseRequest(String reservationId, Long skuId, long quantity) {
}
