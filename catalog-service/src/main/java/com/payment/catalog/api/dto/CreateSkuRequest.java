package com.payment.catalog.api.dto;

/**
 * 创建 SKU 请求。价格以最小货币单位（long）传递，禁止浮点。
 */
public record CreateSkuRequest(String skuCode, Long productId, String name, long priceMinor,
                               String currencyCode, String deliveryDefinition) {
}
