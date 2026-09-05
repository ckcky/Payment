package com.payment.payment.application.reliability;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.support.PaymentTestStack;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终态冲突测试（ADR-0007 / FR-011）：终态吸收一切迟到冲突结果，不覆盖、不重复推进下游。
 *
 * <p>对应 spec Edge Cases「乱序回调」与 ADR-0007「迟到成功不覆盖已失败支付」。</p>
 */
class TerminalConflictTest {

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    private final PaymentTestStack.RecordingOrderGateway order = new PaymentTestStack.RecordingOrderGateway();
    private final PaymentResultProcessor processor =
            new PaymentResultProcessor(payments, attempts, order);

    private Payment savePayment(long paymentId, long attemptId, PaymentStatus status) {
        Payment payment = Payment.rehydrate(paymentId, "PM-" + paymentId, "txn-" + paymentId, "order-" + paymentId, "user-1",
                100, "CNY", "idem-" + paymentId, status, attemptId, null, 0, null, 0, 1);
        payments.save(payment);
        attempts.save(PaymentAttempt.rehydrate(attemptId, "PM-" + paymentId, "mock", 0,
                Instant.now(), null, null, PaymentAttemptStatus.ACCEPTED, null, null, 0));
        return payment;
    }

    @Test
    void lateSuccessDoesNotOverwriteFailedPayment() {
        savePayment(1L, 10L, PaymentStatus.FAILED);

        boolean changed = processor.applyAndNotify("PM-1", ChannelResult.success("late-ref"));

        assertThat(changed).isFalse();
        assertThat(payments.findById(1L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.succeededRequests).isEmpty();
        assertThat(order.succeededRequests).isEmpty();
    }

    @Test
    void lateFailureDoesNotOverwriteSucceededPayment() {
        savePayment(2L, 20L, PaymentStatus.SUCCEEDED);

        boolean changed = processor.applyAndNotify("PM-2", ChannelResult.businessFailure("late-ref", "late decline"));

        assertThat(changed).isFalse();
        assertThat(payments.findById(2L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void unknownDoesNotOverwriteTerminalPayment() {
        savePayment(3L, 30L, PaymentStatus.SUCCEEDED);

        boolean changed = processor.applyAndNotify("PM-3", ChannelResult.timeout("channel glitch"));

        assertThat(changed).isFalse();
        assertThat(payments.findById(3L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
