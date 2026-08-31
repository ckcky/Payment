package com.payment.order.application;

/**
 * 预占库存命令（order-service → catalog-service）。字段名与 catalog 侧请求 DTO 对应以便 JSON 映射。
 */
public record ReserveStockCommand(String reservationId, Long skuId, long quantity) {
}
