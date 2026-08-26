package com.payment.order.application;

/**
 * 下单行（应用层输入）：SKU 引用 + 数量。
 */
public record OrderLine(Long skuId, int quantity) {
}
