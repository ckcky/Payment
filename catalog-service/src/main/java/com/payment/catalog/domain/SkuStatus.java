package com.payment.catalog.domain;

/**
 * SKU 可售状态。只有 {@link #SELLABLE} 的 SKU 才允许加入新订单（data-model 约束）。
 * 转换规则（仅通过 {@link Sku} 领域方法驱动，禁止外部直改）：
 * DRAFT → SELLABLE；SELLABLE → SUSPENDED；SELLABLE/SUSPENDED → DISCONTINUED。
 */
public enum SkuStatus {
    DRAFT,
    SELLABLE,
    SUSPENDED,
    DISCONTINUED
}
