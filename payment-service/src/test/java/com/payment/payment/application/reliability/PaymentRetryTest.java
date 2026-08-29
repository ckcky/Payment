package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentPersistence;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttemptErrorType;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.support.PaymentTestStack;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 有限重试与耗尽测试（spec US3 / FR-005~FR-007 / ADR-0012~0014 修订版）。
 *
 * <p>重试模型自 ADR-0013 修订后为<b>请求内联重试</b>：通信响应码非 {@code SUCCESS} 即在本次请求
 * 线程内退避重放（同一 attempt、幂等键不变），重试期间不落库，最终结果与重试次数一次性写入。
 * 因此本测试不再断言 {@code nextRetryAt} 调度字段，改为断言渠道调用次数与 {@code retries}。</p>
 */
class PaymentRetryTest {

    private static final ChargeRequest REQUEST = new ChargeRequest(1L, 10L, 100L, "CNY", "mock");

    /** 可控渠道桩：按队列依次返回预设结果，耗尽后默认返回成功。 */
    private static final class QueueChannel implements PaymentChannel {

        private final List<ChannelResult> scripted = new ArrayList<>();
        private int index;
        private boolean repeatLast;
        int chargeCalls;

        /** 只返回一次；队列耗尽后恢复成功，用于验证「重试后转成功」。 */
        QueueChannel then(ChannelResult result) {
            scripted.add(result);
            return this;
        }

        /** 恒定返回同一结果（队列耗尽后重复最后一个），用于验证「重试耗尽」。 */
        QueueChannel always(ChannelResult result) {
            scripted.add(result);
            repeatLast = true;
            return this;
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            chargeCalls++;
            if (repeatLast && !scripted.isEmpty()) {
                return scripted.get(scripted.size() - 1);
            }
            if (index < scripted.size()) {
                return scripted.get(index++);
            }
            return ChannelResult.success("mock-ref-final");
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            return ChannelResult.businessUnknown("not used");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            return ChannelResult.businessUnknown("not used");
        }
    }

    private static ReliabilityConfig config(int maxAttempts) {
        ReliabilityConfig config = new ReliabilityConfig();
        config.setRetryMaxAttempts(maxAttempts);
        config.setRetryBackoff(List.of(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        return config;
    }

    @Test
    void transientFailureIsRetriedInlineAndEventuallySucceeds() {
        QueueChannel channel = new QueueChannel()
                .then(ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, "network blip"));
        PaymentRetryService retryService =
                new PaymentRetryService(channel, config(3), new NoopBusinessMetrics());

        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(REQUEST);

        // 首次通信失败 → 内联重放一次后成功：共 2 次渠道调用、1 次重试
        assertThat(channel.chargeCalls).isEqualTo(2);
        assertThat(outcome.retries()).isEqualTo(1);
        assertThat(outcome.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
    }

    @Test
    void hardDeclineIsNotRetried() {
        QueueChannel channel = new QueueChannel()
                .always(ChannelResult.businessFailure("ch-ref-2", "insufficient funds"));
        PaymentRetryService retryService =
                new PaymentRetryService(channel, config(3), new NoopBusinessMetrics());

        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(REQUEST);

        // 业务明确拒绝是结论，不是故障：0 重试（FR-006）
        assertThat(channel.chargeCalls).isEqualTo(1);
        assertThat(outcome.retries()).isZero();
        assertThat(outcome.result().status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(outcome.result().errorType()).isEqualTo(PaymentAttemptErrorType.HARD);
    }

    @Test
    void unknownChannelResultIsNotRetried() {
        QueueChannel channel = new QueueChannel().always(ChannelResult.businessUnknown("still processing"));
        PaymentRetryService retryService =
                new PaymentRetryService(channel, config(3), new NoopBusinessMetrics());

        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(REQUEST);

        // 通信成功但无业务结论：不重试，进 UNKNOWN 由主动查询收敛（ADR-0012）
        assertThat(channel.chargeCalls).isEqualTo(1);
        assertThat(outcome.retries()).isZero();
        assertThat(outcome.result().status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(outcome.result().errorType()).isEqualTo(PaymentAttemptErrorType.UNKNOWN);
    }

    @Test
    void exhaustedRetriesBecomeUnknownAndNeverGuessFailure() {
        QueueChannel channel = new QueueChannel()
                .always(ChannelResult.timeout("no response"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
        PaymentRetryService retryService = new PaymentRetryService(channel, config(2), metrics);

        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(REQUEST);

        // 上限 2 次（含首次）：共 2 次调用、1 次重试后耗尽
        assertThat(channel.chargeCalls).isEqualTo(2);
        assertThat(outcome.retries()).isEqualTo(1);
        assertThat(outcome.result().status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(outcome.result().reason()).isEqualTo(PaymentRetryService.EXHAUSTED_REASON);
        assertThat(outcome.result().errorType()).isEqualTo(PaymentAttemptErrorType.TRANSIENT);
        assertThat(registry.get("payment.retry").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("payment.retry_exhausted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void endToEndRetryThenSuccessNotifiesDownstreamExactlyOnce() {
        InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
        InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        PaymentTestStack.RecordingFulfillmentGateway fulfillment =
                new PaymentTestStack.RecordingFulfillmentGateway();
        QueueChannel channel = new QueueChannel()
                .then(ChannelResult.transportFailure(TransportCode.IO_ERROR, "reset by peer"));
        PaymentRetryService retryService =
                new PaymentRetryService(channel, config(3), new NoopBusinessMetrics());
        PaymentApplicationService appService = new PaymentApplicationService(payments,
                new PaymentPersistence(payments, attempts), retryService, fulfillment,
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        Payment payment = appService.createPaymentIntent(
                new CreatePaymentCommand("txn-1", "order-1", "user-1", 100, "CNY", "idem-1", "mock"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(channel.chargeCalls).isEqualTo(2);
        // 重试期间不落库；收敛后重试次数随本次写入一并落库（ADR-0013）
        assertThat(attempts.findById(payment.getCurrentAttemptId()).orElseThrow().getRetryCount())
                .isEqualTo(1);
        // 同一 attempt 重放：支付成功只推进一次下游，绝不重复履约（ADR-0014 / 最多一次）
        assertThat(fulfillment.succeededRequests).hasSize(1);
    }
}
