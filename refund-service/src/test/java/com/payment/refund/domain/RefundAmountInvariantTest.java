package com.payment.refund.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款金额不变量测试（T049）：可退款金额、累计退款与币种一致性。
 */
class RefundAmountInvariantTest {

    @Test
    void refundableAmountIsPaidMinusCumulativeRefunded() {
        assertThat(RefundPolicy.refundableAmount(10000L, 0L)).isEqualTo(10000L);
        assertThat(RefundPolicy.refundableAmount(10000L, 3000L)).isEqualTo(7000L);
        assertThat(RefundPolicy.refundableAmount(10000L, 10000L)).isEqualTo(0L);
    }

    @Test
    void refundableAmountNeverNegative() {
        assertThat(RefundPolicy.refundableAmount(10000L, 12000L)).isEqualTo(0L);
    }

    @Test
    void fullRefundWithinRefundableIsApproved() {
        RefundDecision decision = RefundPolicy.decide(10000L, "CNY", 10000L, "CNY", 0L);
        assertThat(decision.isApproved()).isTrue();
    }

    @Test
    void cumulativeRefundExceedingPaidIsRejected() {
        RefundDecision decision = RefundPolicy.decide(8000L, "CNY", 10000L, "CNY", 3000L);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).contains("refundable");
    }

    @Test
    void currencyMismatchIsRejected() {
        RefundDecision decision = RefundPolicy.decide(1000L, "USD", 10000L, "CNY", 0L);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).contains("currency mismatch");
    }

    @Test
    void nonPositiveAmountIsRejected() {
        RefundDecision decision = RefundPolicy.decide(0L, "CNY", 10000L, "CNY", 0L);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).contains("positive");
    }
}
