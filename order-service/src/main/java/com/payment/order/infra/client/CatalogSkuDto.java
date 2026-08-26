package com.payment.order.infra.client;

import com.payment.order.application.SkuSnapshot;

/**
 * catalog-service {@code GET /skus/{id}} 的响应镜像（订单服务侧，避免依赖 catalog 模块内部类）。
 * {@code status} 以字符串承接 catalog 的 {@code SkuStatus} 枚举名。
 */
public record CatalogSkuDto(Long id, String skuCode, Long productId, String name, long priceMinor,
                            String currencyCode, String deliveryDefinition, String status) {

    public SkuSnapshot toSnapshot() {
        return new SkuSnapshot(id, skuCode, name, priceMinor, currencyCode, "SELLABLE".equals(status));
    }
}
