package com.payment.order.application;

/**
 * 释放库存命令（order-service → catalog-service）。
 */
public record ReleaseStockCommand(String reservationId, Long skuId, long quantity) {
}
