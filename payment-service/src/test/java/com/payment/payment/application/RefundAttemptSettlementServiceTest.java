package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import org.junit.jupiter.api.Test;

/**
 * 退款尝试收敛测试（fix：UNKNOWN 尝试行随权威结果收敛，不再永久滞留）。纯 JUnit，无 Spring 上下文。
 */
class RefundAttemptSettlementServiceTest {

    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    private final RefundAttemptSettlementService service = new RefundAttemptSettlementService(attempts);

    private PaymentAttempt unknownRefundAttempt(String paymentNo, String channelReference) {
        PaymentAttempt attempt = PaymentAttempt.refundAttempt(paymentNo, "mock", 100, "CNY");
        attempt.accept(channelReference);
        attempt.markUnknown("channel refund accepted in-flight");
        return attempts.save(attempt);
    }

    @Test
    void convergeByChannelReferenceMovesUnknownAttemptToSucceeded() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.success("mock-refund-ref-1"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    void convergeByChannelReferenceMovesUnknownAttemptToFailed() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1",
                ChannelResult.businessFailure("mock-refund-ref-1", "channel declined"));

        PaymentAttempt attempt = attempts.findByPaymentNo("PM-1").get(0);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getFailureReason()).isEqualTo("channel declined");
    }

    @Test
    void convergeWithoutReferenceFallsBackToLatestNonTerminalAttempt() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");
        PaymentAttempt latest = unknownRefundAttempt("PM-1", "mock-refund-ref-2");

        service.convergeRefundAttempt("PM-1", null, ChannelResult.success(null));

        assertThat(latest.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempts.findByPaymentNo("PM-1").stream()
                .filter(a -> "mock-refund-ref-1".equals(a.getChannelReference()))
                .findFirst().orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.UNKNOWN); // 第一条不受影响
    }

    @Test
    void convergeUnknownOutcomeKeepsAttemptNonTerminal() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.businessUnknown("still unknown"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.UNKNOWN);
    }

    @Test
    void convergeRepeatedResultIsAbsorbedByTerminalState() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.success("mock-refund-ref-1"));
        // 重复回调：终态吸收，状态不变
        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.success("mock-refund-ref-1"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    void unmatchedReferenceAdoptsSoleUnconvergedAttemptAndBackfillsReference() {
        // 异步受理渠道未返回引用（channel_reference NULL），回调带引用来收敛：
        // 唯一在途退款尝试归属无歧义 → adopt + 回填引用
        unknownRefundAttempt("PM-1", null);

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-cb",
                ChannelResult.businessFailure("mock-refund-ref-cb", "channel declined"));

        PaymentAttempt attempt = attempts.findByPaymentNo("PM-1").get(0);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getFailureReason()).isEqualTo("channel declined");
        assertThat(attempt.getChannelReference()).isEqualTo("mock-refund-ref-cb");
    }

    @Test
    void unmatchedReferenceWithMultipleUnconvergedDoesNotMisattribute() {
        // 多条在途（归属有歧义）：不臆测归属，保持 UNKNOWN
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");
        unknownRefundAttempt("PM-1", null);

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-404",
                ChannelResult.success("mock-refund-ref-404"));

        assertThat(attempts.findByPaymentNo("PM-1").stream()
                .allMatch(a -> a.getStatus() == PaymentAttemptStatus.UNKNOWN))
                .isTrue();
    }

    @Test
    void convergeIgnoresPaymentTypeAttempts() {
        // PAYMENT 尝试行不受退款收敛影响
        PaymentAttempt paymentAttempt = new PaymentAttempt("PM-1", "mock", 0, 100, "CNY");
        paymentAttempt.accept("mock-pay-ref-1");
        paymentAttempt.markUnknown("pay accepted in-flight");
        attempts.save(paymentAttempt);

        service.convergeRefundAttempt("PM-1", "mock-pay-ref-1", ChannelResult.success("mock-pay-ref-1"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.UNKNOWN);
    }
}
