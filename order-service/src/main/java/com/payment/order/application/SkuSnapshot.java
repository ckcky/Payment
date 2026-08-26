package com.payment.order.application;

/**
 * Catalog SKU 的可售性与价格快照（订单服务对 catalog 的只读视图）。
 * 价格是最小货币单位（long），创建订单时冻结。
 */
public record SkuSnapshot(Long skuId, String skuCode, String name, long priceMinor,
                          String currencyCode, boolean sellable) {
}
