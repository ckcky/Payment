package com.payment.payment.application.reliability;

import com.payment.common.core.observability.NoopBusinessMetrics;
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
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.support.PaymentTestStack;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UNKNOWN 主动查询收敛测试（spec US2 / FR-003/FR-004 / ADR-0003）：
 * 权威成功 → 收敛为成功且只推进一次下游；权威失败 → 收敛为失败且不触发成功回写；
 * 渠道仍不明确 → 保持 UNKNOWN 不猜成败，达到查询上限后停止自动查询。
 */
class ChannelQueryTest {

    /** 可控渠道桩：queryStatus 返回测试设定的结果，并记录被调用次数。 */
    private static final class StubChannel implements PaymentChannel {

        private ChannelResult result = ChannelResult.unknown("inconclusive");
        private int queryCalls;

        @Override
        public ChannelResult charge(ChargeRequest request) {
            return ChannelResult.unknown("not used");
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            return ChannelResult.unknown("not used");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            queryCalls++;
            return result;
        }
    }

    private static final class Harness {

        final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
        final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        final PaymentTestStack.RecordingOrderGateway order = new PaymentTestStack.RecordingOrderGateway();
        final PaymentTestStack.RecordingFulfillmentGateway fulfillment =
                new PaymentTestStack.RecordingFulfillmentGateway();
        final StubChannel channel = new StubChannel();
        final ReliabilityConfig config = new ReliabilityConfig();
        final ChannelQueryService queryService;

        Harness() {
            PaymentResultProcessor processor =
                    new PaymentResultProcessor(payments, attempts, fulfillment, order);
            PaymentUnknownResolutionService resolution = new PaymentUnknownResolutionService(
                    payments, processor, new NoopBusinessMetrics(), new StructuredAuditLogger());
            queryService = new ChannelQueryService(payments, channel, resolution, config,
                    new NoopBusinessMetrics());
        }

        Payment unknownPayment(long paymentId, long attemptId) {
            Payment payment = Payment.rehydrate(paymentId, "txn-" + paymentId, "order-" + paymentId, "user-1",
                    100, "CNY", "idem-" + paymentId, PaymentStatus.UNKNOWN, attemptId, null, 0, null, 0);
            payments.save(payment);
            attempts.save(PaymentAttempt.rehydrate(attemptId, paymentId, "mock", 0,
                    Instant.now().minusSeconds(60), null, null, PaymentAttemptStatus.ACCEPTED,
                    null, null, null, 0));
            return payment;
        }
    }

    @Test
    void authoritativeSuccessConvergesAndNotifiesOnce() {
        Harness h = new Harness();
        h.unknownPayment(1L, 10L);
        h.channel.result = ChannelResult.success("ch-ref-1");

        int converged = h.queryService.queryRound();

        assertThat(converged).isEqualTo(1);
        assertThat(h.payments.findById(1L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(h.order.succeededRequests).hasSize(1);
        assertThat(h.fulfillment.succeededRequests).hasSize(1);
    }

    @Test
    void authoritativeFailureConvergesWithoutSuccessNotification() {
        Harness h = new Harness();
        h.unknownPayment(2L, 20L);
        h.channel.result = ChannelResult.failure("ch-ref-2", "declined by channel");

        int converged = h.queryService.queryRound();

        assertThat(converged).isEqualTo(1);
        assertThat(h.payments.findById(2L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(h.order.succeededRequests).isEmpty();
        assertThat(h.fulfillment.succeededRequests).isEmpty();
    }

    @Test
    void inconclusiveQueryKeepsUnknownUntilAttemptsExhausted() {
        Harness h = new Harness();
        h.config.setQueryMaxAttempts(2);
        h.unknownPayment(3L, 30L);
        h.channel.result = ChannelResult.unknown("still processing");

        assertThat(h.queryService.queryRound()).isZero();
        assertThat(h.payments.findById(3L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(h.queryService.queryRound()).isZero();
        assertThat(h.payments.findById(3L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        // 达到上限后不再自动查询，留待人工/对账收敛（FR-003 / spec 场景3）。
        h.queryService.queryRound();
        assertThat(h.payments.findById(3L).orElseThrow().getQueryAttempts()).isEqualTo(2);
        assertThat(h.channel.queryCalls).isEqualTo(2);
        assertThat(h.order.succeededRequests).isEmpty();
    }

    @Test
    void convergedPaymentIsNotQueriedAgain() {
        Harness h = new Harness();
        h.unknownPayment(4L, 40L);
        h.channel.result = ChannelResult.success("ch-ref-4");

        assertThat(h.queryService.queryRound()).isEqualTo(1);
        int callsAfterConvergence = h.channel.queryCalls;

        assertThat(h.queryService.queryRound()).isZero();

        assertThat(h.channel.queryCalls).isEqualTo(callsAfterConvergence);
        assertThat(h.order.succeededRequests).hasSize(1);
        assertThat(h.fulfillment.succeededRequests).hasSize(1);
    }
}
