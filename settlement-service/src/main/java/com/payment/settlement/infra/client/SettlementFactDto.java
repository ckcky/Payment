package com.payment.settlement.infra.client;

/**
 * reconciliation-service 返回的结算事实 DTO。
 */
public record SettlementFactDto(String reference, String type, long amountMinor, String currencyCode,
                                String merchantId) {

    /** 兼容构造（032 前的 4 字段形态）：merchantId 缺省为 null。 */
    public SettlementFactDto(String reference, String type, long amountMinor, String currencyCode) {
        this(reference, type, amountMinor, currencyCode, null);
    }
}
