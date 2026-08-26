package com.payment.settlement.infra.client;

import java.util.List;

/**
 * reconciliation-service 返回的结算汇总 DTO。
 */
public record ReconciliationSummaryDto(String period, List<SettlementFactDto> facts, int unresolvedDifferenceCount) {
}
