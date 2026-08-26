package com.payment.settlement.domain;

/**
 * 结算明细：参与本次结算的单条财务事实（来自对账事实）。
 *
 * <p>type 取值：PAYMENT / REFUND / ADJUSTMENT。金额一律最小货币单位（long）。</p>
 */
public record SettlementItem(String reference, String type, long amountMinor, String currencyCode) {
}
