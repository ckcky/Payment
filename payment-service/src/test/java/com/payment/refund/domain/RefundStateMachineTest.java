package com.payment.refund.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款状态机测试（T048）：验证合法转换、终态保护与非法跳转拒绝。
 */
class RefundStateMachineTest {

    private Refund newRefund() {
        return new Refund("order-1", "PM-1", "user-1", 1000L, "CNY", "customer request",
                "idem-1", List.of(new RefundItem("item-1", 1000L)));
    }

    @Test
    void requestToProcessingToSucceeded() {
        Refund refund = newRefund();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);

        refund.process();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);

        assertThat(refund.succeed()).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void unknownConvergesToSucceeded() {
        Refund refund = newRefund();
        refund.process();
        assertThat(refund.markUnknown("channel timeout")).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);

        assertThat(refund.succeed()).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void unknownConvergesToFailed() {
        Refund refund = newRefund();
        refund.process();
        refund.markUnknown("channel timeout");

        assertThat(refund.fail("channel declined")).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo("channel declined");
    }

    @Test
    void terminalAbsorbsLateConflictingResult() {
        Refund refund = newRefund();
        refund.process();
        assertThat(refund.succeed()).isTrue();

        // 终态成功不被后到的失败覆盖
        assertThat(refund.fail("late decline")).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void requestToRejectedToClosed() {
        Refund refund = newRefund();
        refund.reject("exceeds refundable");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);

        refund.close();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.CLOSED);
    }

    @Test
    void succeedBeforeProcessIsRejected() {
        Refund refund = newRefund();
        assertThatThrownBy(refund::succeed)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void partialRefundRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new Refund("order-1", "PM-1", "user-1", 0L, "CNY",
                "reason", "idem-1", List.of()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION));
    }
}
