package com.payment.order.application;

/**
 * 确认扣减命令（order-service → catalog-service）。deductId 为支付单号（幂等键）。
 */
public record ConfirmStockCommand(String reservationId, Long skuId, long quantity, String deductId) {
}
