package com.payment.settlement.application;

/**
 * 结算财务事实（本地端口值对象）：来自对账确认事实，type 取值 PAYMENT / REFUND / ADJUSTMENT。
 *
 * @param merchantId 商户号（032/G2：事实归属商户，结算商户校验依据；可为 null = 兼容窗口）
 */
public record SettlementFact(String reference, String type, long amountMinor, String currencyCode,
                             String merchantId) {

    /** 兼容构造（032 前的 4 字段形态）：merchantId 缺省为 null。 */
    public SettlementFact(String reference, String type, long amountMinor, String currencyCode) {
        this(reference, type, amountMinor, currencyCode, null);
    }
}
