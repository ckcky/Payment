package com.payment.catalog.api.dto;

import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuStatus;

/**
 * SKU 响应 DTO。价格以最小货币单位（long）返回。
 */
public record SkuResponse(Long id, String skuCode, Long productId, String name, long priceMinor,
                          String currencyCode, String deliveryDefinition, SkuStatus status) {

    public static SkuResponse from(Sku sku) {
        return new SkuResponse(
                sku.getId(),
                sku.getSkuCode(),
                sku.getProductId(),
                sku.getName(),
                sku.getPriceMinor(),
                sku.getCurrencyCode(),
                sku.getDeliveryDefinition(),
                sku.getStatus());
    }
}
