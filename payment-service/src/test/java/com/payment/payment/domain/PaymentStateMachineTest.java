package com.payment.payment.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付与支付尝试状态机测试（T023）：状态转换与终态保护。
 */
class PaymentStateMachineTest {

    private static Payment payment() {
        return new Payment("txn-1", "order-1", "user-1", 100, "CNY", "idem-1");
    }

    private static PaymentAttempt attempt() {
        return new PaymentAttempt(1L, "mock", 0);
    }

    // ---- Payment ----

    @Test
    void startMovesToProcessing() {
        Payment p = payment();
        p.start(1L);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(p.getCurrentAttemptId()).isEqualTo(1L);
    }

    @Test
    void succeedFromProcessing() {
        Payment p = payment();
        p.start(1L);
        assertThat(p.succeed()).isTrue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void duplicateSucceedIsIdempotent() {
        Payment p = payment();
        p.start(1L);
        p.succeed();
        assertThat(p.succeed()).isFalse();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void lateFailCannotOverwriteSuccess() {
        Payment p = payment();
        p.start(1L);
        p.succeed();
        assertThat(p.fail("late failure")).isFalse();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void failFromProcessing() {
        Payment p = payment();
        p.start(1L);
        assertThat(p.fail("declined")).isTrue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("declined");
    }

    @Test
    void lateSuccessCannotOverwriteFailure() {
        Payment p = payment();
        p.start(1L);
        p.fail("declined");
        assertThat(p.succeed()).isFalse();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void markUnknownFromProcessing() {
        Payment p = payment();
        p.start(1L);
        assertThat(p.markUnknown("timeout")).isTrue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void succeedFromUnknownConverges() {
        Payment p = payment();
        p.start(1L);
        p.markUnknown("timeout");
        assertThat(p.succeed()).isTrue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void closeFromSucceeded() {
        Payment p = payment();
        p.start(1L);
        p.succeed();
        p.close();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CLOSED);
    }

    @Test
    void closeFromPendingThrows() {
        Payment p = payment();
        assertThatThrownBy(p::close)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void succeedFromPendingThrows() {
        Payment p = payment();
        assertThatThrownBy(p::succeed)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    // ---- PaymentAttempt ----

    @Test
    void attemptAcceptThenSucceed() {
        PaymentAttempt a = attempt();
        assertThat(a.accept("ref-1")).isTrue();
        assertThat(a.succeed()).isTrue();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(a.getChannelReference()).isEqualTo("ref-1");
    }

    @Test
    void attemptMarkUnknownThenConverge() {
        PaymentAttempt a = attempt();
        assertThat(a.markUnknown("timeout")).isTrue();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(a.succeed()).isTrue();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    void attemptLateFailAfterSuccessIsAbsorbed() {
        PaymentAttempt a = attempt();
        a.accept("ref-1");
        a.succeed();
        assertThat(a.fail("late")).isFalse();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }
}
