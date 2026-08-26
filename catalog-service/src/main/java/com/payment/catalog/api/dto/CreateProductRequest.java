package com.payment.catalog.api.dto;

/**
 * 创建商品请求。
 */
public record CreateProductRequest(String productCode, String name, String type) {
}
