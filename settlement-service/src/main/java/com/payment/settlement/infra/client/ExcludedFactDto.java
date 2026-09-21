package com.payment.settlement.infra.client;

/**
 * reconciliation-service 返回的结算扣减项 DTO（032/G4 excludedFacts）。
 */
public record ExcludedFactDto(String reference, String type, long amountMinor, String reason) {
}
