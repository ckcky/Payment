package com.payment.settlement.domain;

/**
 * 结算调整项（值对象）：人工/系统对净额的调整，MVP 阶段不参与计算，保留契约。
 */
public record Adjustment(String reason, long amountMinor, String currencyCode) {
}
