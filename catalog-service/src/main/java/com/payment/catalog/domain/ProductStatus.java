package com.payment.catalog.domain;

/**
 * Product 生命周期状态。
 * 转换规则（仅通过 {@link Product} 领域方法驱动，禁止外部直改）：
 * DRAFT → LISTED → UNLISTED → ARCHIVED。
 */
public enum ProductStatus {
    DRAFT,
    LISTED,
    UNLISTED,
    ARCHIVED
}
