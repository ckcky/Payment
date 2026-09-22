package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.reliability.PaymentRetryService.RetryOutcome;
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
        public String channelCode() {
            return "MOCK";
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            return chargeResult;
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            return ChannelResult.businessUnknown("not used");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            return queryResult;
        }
    }

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    private final PaymentTestStack.RecordingOrderGateway order = new PaymentTestStack.RecordingOrderGateway();
    private final FixedChannel channel = new FixedChannel();
    private final ReliabilityConfig config = new ReliabilityConfig();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

    private final PaymentResultProcessor processor =
            new PaymentResultProcessor(payments, attempts, order);
    private final PaymentUnknownResolutionService resolution =
            new PaymentUnknownResolutionService(payments, processor, metrics, new StructuredAuditLogger());
    private final PaymentRetryService retryService =
            new PaymentRetryService(channel, config, metrics);
    private final ChannelQueryService queryService =
            new ChannelQueryService(payments, channel, resolution, config, metrics);

    private Payment savePayment(long paymentId, long attemptId, PaymentStatus status) {
        Payment payment = Payment.rehydrate(paymentId, "PM-" + paymentId, "txn-" + paymentId, "order-" + paymentId, "user-1",
                100, "CNY", "idem-" + paymentId, status, attemptId, null, 0, null, 0, 1, "M001");
        payments.save(payment);
        attempts.save(PaymentAttempt.rehydrate(attemptId, "PM-" + paymentId, "mock", 0,
                Instant.now().minusSeconds(120), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, 0, 0L, "CNY"));
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
    void transportFailureRetryAndExhaustionEmitCounters() {
        config.setRetryMaxAttempts(2); // 含首次共 2 次渠道调用
        config.setRetryBackoff(List.of(Duration.ofMillis(1)));
        channel.chargeResult = ChannelResult.timeout("blip");
        savePayment(2L, 20L, PaymentStatus.PROCESSING);

        // 通信失败在本次调用内联重放一次后耗尽（ADR-0012/0013）
        RetryOutcome outcome = retryService.chargeWithRetry(null);

        assertThat(outcome.retries()).isEqualTo(1);
        assertThat(countOf("payment.retry")).isEqualTo(1.0);
        assertThat(countOf("payment.retry_exhausted")).isEqualTo(1.0);
        assertThat(outcome.result().reason()).isEqualTo(PaymentRetryService.EXHAUSTED_REASON);
        assertThat(outcome.result().status()).isEqualTo(ChannelResult.Status.UNKNOWN);
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

        // spec 035 / T32（A-03 信号源）：渠道出站查询进 channel_request{channelCode,result}
        assertThat(registry.find("channel_request").tags("channelCode", "MOCK", "result", "success")
                .counter()).isNotNull();
        assertThat(registry.get("channel_request").tags("channelCode", "MOCK", "result", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("channel_timeout").tags("channelCode", "MOCK").counter())
                .as("明确成功不计渠道超时").isNull();
    }

    @Test
    void channelUnknownResultCountedAsChannelTimeoutProxy() {
        // 渠道契约：超时/断连 MUST 映射 UNKNOWN ⇒ UNKNOWN 结果 = channel_timeout 唯一可观测代理
        savePayment(4L, 40L, PaymentStatus.PROCESSING);
        new TimeoutScanner(payments, attempts, metrics, config).scan(Instant.now());
        channel.queryResult = ChannelResult.businessUnknown("still pending");

        queryService.queryRound();

        assertThat(registry.get("channel_request").tags("channelCode", "MOCK", "result", "unknown")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("channel_timeout").tags("channelCode", "MOCK").counter().count())
                .isEqualTo(1.0);
    }
}
