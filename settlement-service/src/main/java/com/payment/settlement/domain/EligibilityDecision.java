package com.payment.settlement.domain;

/**
 * 结算资格决策（值对象）：由 {@link SettlementEligibility} 生成，记录是否合格与原因。
 */
public record EligibilityDecision(boolean eligible, String reason) {
}
