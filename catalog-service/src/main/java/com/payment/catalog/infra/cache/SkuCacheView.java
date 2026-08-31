package com.payment.catalog.infra.cache;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuStatus;

/**
 * SKU 缓存视图（014）：用于 Redis 中 JSON 序列化的扁平快照。
 * 显式标注 {@link JsonProperty} 以在不开启 {@code -parameters} 编译参数时也能可靠反序列化。
 */
public record SkuCacheView(
        @JsonProperty("id") Long id,
        @JsonProperty("skuCode") String skuCode,
        @JsonProperty("productId") Long productId,
        @JsonProperty("name") String name,
        @JsonProperty("priceMinor") long priceMinor,
        @JsonProperty("currencyCode") String currencyCode,
        @JsonProperty("deliveryDefinition") String deliveryDefinition,
        @JsonProperty("status") String status,
        @JsonProperty("version") Integer version) {

    public static SkuCacheView from(Sku s) {
        return new SkuCacheView(s.getId(), s.getSkuCode(), s.getProductId(), s.getName(),
                s.getPriceMinor(), s.getCurrencyCode(), s.getDeliveryDefinition(),
                s.getStatus().name(), s.getVersion());
    }

    public Sku toSku() {
        return Sku.rehydrate(id, skuCode, productId, name, priceMinor, currencyCode,
                deliveryDefinition, SkuStatus.valueOf(status), version);
    }
}
