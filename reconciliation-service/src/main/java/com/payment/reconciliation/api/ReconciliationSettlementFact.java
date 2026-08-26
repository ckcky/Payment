package com.payment.reconciliation.api;

/**
 * 结算汇总事实：一条一致匹配记录（reference + 类型 + 金额 + 币种）。
 * settlement-service 据此计算净额，无需回查原始支付/退款事实。
 */
public record ReconciliationSettlementFact(String reference, String type, long amountMinor,
                                           String currencyCode) {
}
