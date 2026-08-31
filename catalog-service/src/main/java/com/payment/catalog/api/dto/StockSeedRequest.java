package com.payment.catalog.api.dto;

/**
 * 库存初始化请求（演示/测试用）。
 */
public record StockSeedRequest(Long skuId, long total) {
}
