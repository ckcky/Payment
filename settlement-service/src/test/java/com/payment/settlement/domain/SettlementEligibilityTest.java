package com.payment.settlement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结算资格判定测试（T060）：商户资格与未解决对账差异。
 */
class SettlementEligibilityTest {

    @Test
    void merchantNotEligibleRejects() {
        EligibilityDecision decision = SettlementEligibility.evaluate(false, 0);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.reason()).isEqualTo("merchant not eligible for settlement");
    }

    @Test
    void unresolvedDifferencesReject() {
        EligibilityDecision decision = SettlementEligibility.evaluate(true, 1);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.reason()).isEqualTo("unresolved reconciliation differences present");
    }

    @Test
    void eligibleMerchantAndNoDifferencesIsEligible() {
        EligibilityDecision decision = SettlementEligibility.evaluate(true, 0);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.reason()).isNull();
    }
}
