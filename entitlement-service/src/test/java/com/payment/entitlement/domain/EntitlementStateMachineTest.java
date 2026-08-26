package com.payment.entitlement.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 权益状态机：合法迁移与非法迁移（非法迁移抛 {@link BizException}）。
 */
class EntitlementStateMachineTest {

    private static Entitlement pending(int quantity) {
        return new Entitlement("user_1", "order_1", "ful_1", quantity, "default", null);
    }

    @Test
    void grantsFromPendingToAvailable() {
        Entitlement e = pending(1);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.PENDING_GRANT);
        e.grant();
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.AVAILABLE);
    }

    @Test
    void consumesAvailableToPartiallyUsedThenExhausted() {
        Entitlement e = pending(3);
        e.grant();
        e.consume(1);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.PARTIALLY_USED);
        assertThat(e.getAvailableQuantity()).isEqualTo(2);
        e.consume(2);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.EXHAUSTED);
        assertThat(e.getAvailableQuantity()).isZero();
    }

    @Test
    void expiresFromAvailable() {
        Entitlement e = pending(1);
        e.grant();
        e.expire();
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.EXPIRED);
    }

    @Test
    void revokesFromAvailable() {
        Entitlement e = pending(1);
        e.grant();
        e.revoke();
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.REVOKED);
    }

    @Test
    void failsFromPending() {
        Entitlement e = pending(1);
        e.fail("grant rejected");
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.FAILED);
    }

    @Test
    void rejectsGrantTwice() {
        Entitlement e = pending(1);
        e.grant();
        assertThatThrownBy(e::grant)
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION);
    }

    @Test
    void rejectsConsumingMoreThanAvailable() {
        Entitlement e = pending(1);
        e.grant();
        assertThatThrownBy(() -> e.consume(2))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION);
    }

    @Test
    void rejectsConsumingNonPositiveQuantity() {
        Entitlement e = pending(1);
        e.grant();
        assertThatThrownBy(() -> e.consume(0))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION);
    }

    @Test
    void rejectsConsumingFromPendingGrant() {
        Entitlement e = pending(1);
        assertThatThrownBy(() -> e.consume(1))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION);
    }
}
