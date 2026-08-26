package com.payment.settlement.infra.client;

/**
 * reconciliation-service 返回的结算事实 DTO。
 */
public record SettlementFactDto(String reference, String type, long amountMinor, String currencyCode) {
}
