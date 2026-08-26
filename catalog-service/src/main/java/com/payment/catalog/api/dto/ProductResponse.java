package com.payment.catalog.api.dto;

import com.payment.catalog.domain.Product;
import com.payment.catalog.domain.ProductStatus;

/**
 * 商品响应 DTO。
 */
public record ProductResponse(Long id, String productCode, String name, String type, ProductStatus status) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getType(),
                product.getStatus());
    }
}
