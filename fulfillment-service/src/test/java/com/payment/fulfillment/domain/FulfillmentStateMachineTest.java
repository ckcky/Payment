package com.payment.fulfillment.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约状态机（T040）：合法迁移与非法迁移（抛 STATE_TRANSITION_VIOLATION）。
 */
class FulfillmentStateMachineTest {

    private static Fulfillment newFulfillment() {
        return new Fulfillment("order_1", "item_1", "mock delivery", "pay_1");
    }

    @Test
    void legalTransitionPendingToProcessingToDelivered() {
        Fulfillment f = newFulfillment();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PENDING);

        f.start();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);

        f.deliver();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
    }

    @Test
    void legalTransitionPendingToCancelled() {
        Fulfillment f = newFulfillment();
        f.cancel();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    }

    @Test
    void legalTransitionProcessingToFailed() {
        Fulfillment f = newFulfillment();
        f.start();
        f.fail("mock failure");
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(f.getFailureReason()).isEqualTo("mock failure");
    }

    @Test
    void deliverFromPendingThrows() {
        Fulfillment f = newFulfillment();
        assertThatThrownBy(f::deliver)
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.STATE_TRANSITION_VIOLATION);
    }

    @Test
    void startTwiceThrows() {
        Fulfillment f = newFulfillment();
        f.start();
        assertThatThrownBy(f::start)
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.STATE_TRANSITION_VIOLATION);
    }

    @Test
    void deliverAfterDeliveredThrows() {
        Fulfillment f = newFulfillment();
        f.start();
        f.deliver();
        assertThatThrownBy(f::deliver)
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.STATE_TRANSITION_VIOLATION);
    }
}
