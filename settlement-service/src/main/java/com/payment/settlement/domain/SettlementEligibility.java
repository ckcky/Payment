package com.payment.settlement.domain;

/**
 * 结算资格判定（纯领域函数，无副作用）。
 *
 * <p>商户必须为 ACTIVE 且 settlementEligible，且对账不得存在未解决差异，方可生成结算批次。</p>
 */
public final class SettlementEligibility {

    private SettlementEligibility() {
    }

    /**
     * 判定商户本期是否可结算。
     *
     * @param merchantActiveAndEligible 商户状态为 ACTIVE 且标记为可结算
     * @param unresolvedDifferenceCount 对账未解决差异条数
     */
    public static EligibilityDecision evaluate(boolean merchantActiveAndEligible, int unresolvedDifferenceCount) {
        if (!merchantActiveAndEligible) {
            return new EligibilityDecision(false, "merchant not eligible for settlement");
        }
        if (unresolvedDifferenceCount > 0) {
            return new EligibilityDecision(false, "unresolved reconciliation differences present");
        }
        return new EligibilityDecision(true, null);
    }
}
