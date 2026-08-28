package com.payment.payment.application.reliability;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptErrorType;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.support.PaymentTestStack;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 有限重试与耗尽测试（spec US3 / FR-005~FR-007 / ADR-0005、ADR-0012~0014）：
 * 瞬时失败按上限与退避重试并最终成功；硬拒绝 0 重试直接进失败；
 * 渠道不明确不重试直接进 UNKNOWN；重试耗尽且不确定 → UNKNOWN（不误判成败）。
 */
class PaymentRetryTest {

    /** 可控渠道桩：按队列依次返回预设结果，耗尽后默认返回成功。 */
    private static final class QueueChannel implements PaymentChannel {

        private final List<ChannelResult> scripted = new ArrayList<>();
        private int index;
        int chargeCalls;

        QueueChannel then(ChannelResult result) {
            scripted.add(result);
            return this;
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            chargeCalls++;
            if (index < scripted.size()) {
                return scripted.get(index++);
            }
            return ChannelResult.success("mock-ref-final");
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            return ChannelResult.unknown("not used");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            return ChannelResult.unknown("not used");
        }
    }

    private static final class Harness {

        final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
        final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        final PaymentTestStack.RecordingOrderGateway order = new PaymentTestStack.RecordingOrderGateway();
        final PaymentTestStack.RecordingFulfillmentGateway fulfillment =
                new PaymentTestStack.RecordingFulfillmentGateway();
        final QueueChannel channel = new QueueChannel();
        final ReliabilityConfig config = new ReliabilityConfig();
        final PaymentRetryService retryService;

        Harness() {
            PaymentResultProcessor processor =
                    new PaymentResultProcessor(payments, attempts, fulfillment, order);
            retryService = new PaymentRetryService(payments, attempts, channel, processor, config,
                    new NoopBusinessMetrics());
        }

        Payment processingPayment(long paymentId, long attemptId) {
            Payment payment = Payment.rehydrate(paymentId, "txn-" + paymentId, "order-" + paymentId, "user-1",
                    100, "CNY", "idem-" + paymentId, PaymentStatus.PROCESSING, attemptId, null, 0, null, 0);
            payments.save(payment);
            attempts.save(PaymentAttempt.rehydrate(attemptId, paymentId, "mock", 0,
                    Instant.now(), null, null, PaymentAttemptStatus.PENDING, null, null, null, 0));
            return payment;
        }
    }

    @Test
    void transientFailureIsRetriedWithBackoffAndEventuallySucceeds() {
        Harness h = new Harness();
        h.config.setRetryMaxAttempts(3);
        h.config.setRetryBackoff(List.of(Duration.ofMillis(1), Duration.ofMillis(1)));
        Payment payment = h.processingPayment(1L, 10L);
        h.channel.then(ChannelResult.transientFailure("network blip"));

        // 首次瞬时失败：安排重试，支付保持 PROCESSING
        Payment handled = h.retryService.tryHandleRetryable(payment.getId(), 10L,
                h.channel.charge(null));
        assertThat(handled).isNotNull();
        assertThat(h.payments.findById(1L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(h.attempts.findById(10L).orElseThrow().getNextRetryAt()).isNotNull();

        // 退避到期后重放：本次渠道成功 → 收敛为 SUCCEEDED 并只推进一次下游
        int executed = h.retryService.retryDue(Instant.now().plusSeconds(1));

        assertThat(executed).isEqualTo(1);
        assertThat(h.payments.findById(1L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(h.order.succeededRequests).hasSize(1);
        assertThat(h.fulfillment.succeededRequests).hasSize(1);
    }

    @Test
    void hardDeclineIsNotRetried() {
        Harness h = new Harness();
        h.config.setRetryMaxAttempts(3);
        Payment payment = h.processingPayment(2L, 20L);

        Payment handled = h.retryService.tryHandleRetryable(payment.getId(), 20L,
                ChannelResult.failure("ch-ref-2", "insufficient funds"));

        assertThat(handled).isNull(); // 硬拒绝不重试，交由既有流程直接进 FAILED
        assertThat(h.attempts.findById(20L).orElseThrow().getNextRetryAt()).isNull();
        assertThat(h.payments.findById(2L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void unknownChannelResultIsNotRetried() {
        Harness h = new Harness();
        h.config.setRetryMaxAttempts(3);
        Payment payment = h.processingPayment(3L, 30L);

        Payment handled = h.retryService.tryHandleRetryable(payment.getId(), 30L,
                ChannelResult.unknown("channel timeout"));

        assertThat(handled).isNull(); // 渠道不明确不重试，直接进 UNKNOWN（ADR-0012）
        assertThat(h.attempts.findById(30L).orElseThrow().getNextRetryAt()).isNull();
    }

    @Test
    void exhaustedRetriesBecomeUnknown() {
        Harness h = new Harness();
        h.config.setRetryMaxAttempts(2); // 含首次共 2 次机会
        h.config.setRetryBackoff(List.of(Duration.ofMillis(1)));
        Payment payment = h.processingPayment(4L, 40L);
        h.channel.then(ChannelResult.transientFailure("blip-1"));
        h.channel.then(ChannelResult.transientFailure("blip-2"));

        // 第一次（attemptsMade=1 < 2）→ 安排重试
        h.retryService.tryHandleRetryable(payment.getId(), 40L, h.channel.charge(null));
        assertThat(h.payments.findById(4L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        // 重放第二次（attemptsMade=2，达到上限）→ 耗尽进 UNKNOWN，绝不臆断失败
        h.retryService.retryDue(Instant.now().plusSeconds(1));

        Payment reloaded = h.payments.findById(4L).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(reloaded.getFailureReason()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(h.attempts.findById(40L).orElseThrow().getErrorType())
                .isEqualTo(PaymentAttemptErrorType.TRANSIENT);
        assertThat(h.order.succeededRequests).isEmpty();
    }
}
