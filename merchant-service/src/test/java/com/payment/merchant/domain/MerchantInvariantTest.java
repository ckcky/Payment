package com.payment.merchant.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantInvariantTest {

    @Test
    void newMerchantIsPendingReviewAndNotEligible() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING_REVIEW);
        assertThat(merchant.isEligibleForSettlement()).isFalse();
    }

    @Test
    void approveActivatesAndMakesEligible() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");

        merchant.approve();

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.isEligibleForSettlement()).isTrue();
    }

    @Test
    void suspendFromActiveSuspendsAndDisablesEligibility() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");
        merchant.approve();

        merchant.suspend();

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.SUSPENDED);
        assertThat(merchant.isEligibleForSettlement()).isFalse();
    }

    @Test
    void terminateMovesToTerminated() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");

        merchant.terminate();

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.TERMINATED);
    }

    @Test
    void approveFromActiveThrows() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");
        merchant.approve();

        assertThatThrownBy(merchant::approve)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void suspendFromPendingReviewThrows() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");

        assertThatThrownBy(merchant::suspend)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void terminateTwiceThrows() {
        Merchant merchant = new Merchant("M-001", "Acme", "settlement-1");
        merchant.terminate();

        assertThatThrownBy(merchant::terminate)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }
}
