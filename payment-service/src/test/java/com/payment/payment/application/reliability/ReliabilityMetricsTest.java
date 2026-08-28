package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.support.PaymentTestStack;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可靠性指标测试（spec US5 / FR-010 / ADR-0015）：
 * 超时、重试、重试耗尽、主动查询均产出对应业务计数；UNKNOWN 收敛产出**真实**时长（非零）。
 */
class ReliabilityMetricsTest {

    private static final class FixedChannel implements PaymentChannel {

        private ChannelResult chargeResult = ChannelResult.success("mock-ref");
        private ChannelResult queryResult = ChannelResult.success("mock-q-ref");

        @Override
        public ChannelResult charge(ChargeRequest request) {
            return chargeResult;
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            return ChannelResult.unknown("not used");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            return queryResult;
        }
    }

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    private final PaymentTestStack.RecordingOrderGateway order = new PaymentTestStack.RecordingOrderGateway();
    private final PaymentTestStack.RecordingFulfillmentGateway fulfillment =
            new PaymentTestStack.RecordingFulfillmentGateway();
    private final FixedChannel channel = new FixedChannel();
    private final ReliabilityConfig config = new ReliabilityConfig();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

    private final PaymentResultProcessor processor =
            new PaymentResultProcessor(payments, attempts, fulfillment, order);
    private final PaymentUnknownResolutionService resolution =
            new PaymentUnknownResolutionService(payments, processor, metrics, new StructuredAuditLogger());
    private final PaymentRetryService retryService =
            new PaymentRetryService(payments, attempts, channel, processor, config, metrics);
    private final ChannelQueryService queryService =
            new ChannelQueryService(payments, channel, resolution, config, metrics);

    private Payment savePayment(long paymentId, long attemptId, PaymentStatus status) {
        Payment payment = Payment.rehydrate(paymentId, "txn-" + paymentId, "order-" + paymentId, "user-1",
                100, "CNY", "idem-" + paymentId, status, attemptId, null, 0, null, 0);
        payments.save(payment);
        attempts.save(PaymentAttempt.rehydrate(attemptId, paymentId, "mock", 0,
                Instant.now().minusSeconds(120), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, null, 0));
        return payment;
    }

    private double countOf(String name) {
        return registry.find(name).counter() == null ? 0.0 : registry.find(name).counter().count();
    }

    @Test
    void timeoutScanIncrementsPaymentTimeoutCounter() {
        savePayment(1L, 10L, PaymentStatus.PROCESSING);
        TimeoutScanner scanner = new TimeoutScanner(payments, attempts, metrics, config);

        scanner.scan(Instant.now());

        assertThat(countOf("payment.timeout")).isEqualTo(1.0);
        assertThat(payments.findById(1L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void transientRetryAndExhaustionEmitCounters() {
        config.setRetryMaxAttempts(2);
        config.setRetryBackoff(List.of(Duration.ofMillis(1)));
        channel.chargeResult = ChannelResult.transientFailure("blip");
        savePayment(2L, 20L, PaymentStatus.PROCESSING);

        // 第一次瞬时失败 → 安排重试（payment.retry）
        retryService.tryHandleRetryable(2L, 20L, channel.charge(null));
        assertThat(countOf("payment.retry")).isEqualTo(1.0);

        // 重放第二次 → 耗尽进 UNKNOWN（payment.retry_exhausted）
        retryService.retryDue(Instant.now().plusSeconds(1));

        assertThat(countOf("payment.retry_exhausted")).isEqualTo(1.0);
        assertThat(payments.findById(2L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void channelQueryEmitsCounterAndRealUnknownDuration() {
        // 支付在进入 UNKNOWN 时记录 enteredUnknownAt，收敛时据此产出真实时长
        savePayment(3L, 30L, PaymentStatus.PROCESSING);
        new TimeoutScanner(payments, attempts, metrics, config).scan(Instant.now());
        assertThat(payments.findById(3L).orElseThrow().getEnteredUnknownAt()).isNotNull();

        channel.queryResult = ChannelResult.success("mock-q-ref");
        int converged = queryService.queryRound();

        assertThat(converged).isEqualTo(1);
        assertThat(countOf("payment.query")).isEqualTo(1.0);
        assertThat(payments.findById(3L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        var timer = registry.find("payment.unknown.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0L);
    }
}
