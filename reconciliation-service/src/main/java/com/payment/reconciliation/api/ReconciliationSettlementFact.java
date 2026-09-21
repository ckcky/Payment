package com.payment.reconciliation.api;

/**
 * 结算汇总事实：一条一致匹配记录（reference + 类型 + 金额 + 币种）。
 * settlement-service 据此计算净额，无需回查原始支付/退款事实。
 *
 * @param merchantId 商户号（032/G2；可为 null = 兼容窗口）
 */
public record ReconciliationSettlementFact(String reference, String type, long amountMinor,
                                           String currencyCode, String merchantId) {

    /** 兼容构造（032 前的 4 字段形态）：merchantId 缺省为 null。 */
    public ReconciliationSettlementFact(String reference, String type, long amountMinor, String currencyCode) {
        this(reference, type, amountMinor, currencyCode, null);
    }
}
