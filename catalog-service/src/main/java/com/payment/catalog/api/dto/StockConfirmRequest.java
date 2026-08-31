package com.payment.catalog.api.dto;

/**
 * 确认扣减请求（order-service → catalog-service），deductId 为支付单号（幂等键）。
 */
public record StockConfirmRequest(String reservationId, Long skuId, long quantity, String deductId) {
}
