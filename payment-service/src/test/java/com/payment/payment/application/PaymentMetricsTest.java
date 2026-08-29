package com.payment.payment.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.application.PaymentPersistence;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付业务指标落地测试（T072）：用 Micrometer 真实落盘并断言计数器/计时器增长。
 */
class PaymentMetricsTest {

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    private final PaymentTestStack.RecordingFulfillmentGateway fulfillment =
            new PaymentTestStack.RecordingFulfillmentGateway();
    private final PaymentTestStack.RecordingOrderGateway order =
            new PaymentTestStack.RecordingOrderGateway();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
    private final StructuredAuditLogger audit = new StructuredAuditLogger();

    private PaymentApplicationService appService(MockChannelAdapter channel) {
        PaymentResultProcessor processor =
                new PaymentResultProcessor(payments, attempts, fulfillment, order);
        PaymentRetryService retryService = new PaymentRetryService(channel,
                PaymentTestStack.fastRetryConfig(), metrics);
        return new PaymentApplicationService(payments, new PaymentPersistence(payments, attempts),
                retryService, fulfillment, metrics, audit);
    }

    private CreatePaymentCommand command(String idempotencyKey) {
        return new CreatePaymentCommand("txn-1", "order-1", "user-1", 100, "CNY", idempotencyKey, "mock");
    }

    @Test
    void createSuccessIncrementsCreatedAndSucceeded() {
        Payment payment = appService(new MockChannelAdapter()).createPaymentIntent(command("k1"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(registry.get("payment.created").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("payment.succeeded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void createFailureIncrementsFailed() {
        Payment payment = appService(new MockChannelAdapter(MockChannelAdapter.Scenario.FAILURE))
                .createPaymentIntent(command("k1"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(registry.get("payment.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void createTimeoutIncrementsUnknown() {
        Payment payment = appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(command("k1"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(registry.get("payment.unknown").counter().count()).isEqualTo(1.0);
    }

    @Test
    void duplicateCallbackIncrementsDuplicateCounter() {
        Payment payment = appService(new MockChannelAdapter()).createPaymentIntent(command("k1"));

        PaymentResultProcessor processor = new PaymentResultProcessor(payments, attempts, fulfillment, order);
        PaymentCallbackService callback = new PaymentCallbackService(processor, payments, metrics, audit);

        boolean changed = callback.handleCallback(payment.getId(), ChannelResult.success("late-ref"));

        assertThat(changed).isFalse();
        assertThat(registry.get("payment.duplicate_callback").counter().count()).isEqualTo(1.0);
    }

    @Test
    void unknownResolutionRecordsSucceededAndDuration() {
        Payment payment = appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        PaymentResultProcessor processor = new PaymentResultProcessor(payments, attempts, fulfillment, order);
        PaymentUnknownResolutionService resolution =
                new PaymentUnknownResolutionService(payments, processor, metrics, audit);

        boolean resolved = resolution.resolve(payment.getId(), ChannelResult.success("authoritative"));

        assertThat(resolved).isTrue();
        assertThat(registry.get("payment.succeeded").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("payment.unknown.duration").timer().count()).isEqualTo(1);
    }
}
