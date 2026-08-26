package com.payment.settlement.application;

/**
 * 结算财务事实（本地端口值对象）：来自对账确认事实，type 取值 PAYMENT / REFUND / ADJUSTMENT。
 */
public record SettlementFact(String reference, String type, long amountMinor, String currencyCode) {
}
