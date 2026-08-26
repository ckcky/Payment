package com.payment.reconciliation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一致匹配记录：平台事实与渠道账单在同一引用上金额与状态完全一致。
 *
 * <p>额外携带 {@code amountMinor} / {@code currencyCode} 以便结算侧从匹配事实直接取金额，
 * 无需回查原始支付/退款事实。</p>
 */
public record Match(String reference, String type, long amountMinor, String currencyCode) {

    @JsonCreator
    public Match(
            @JsonProperty("reference") String reference,
            @JsonProperty("type") String type,
            @JsonProperty("amountMinor") long amountMinor,
            @JsonProperty("currencyCode") String currencyCode) {
        this.reference = reference;
        this.type = type;
        this.amountMinor = amountMinor;
        this.currencyCode = currencyCode;
    }
}
