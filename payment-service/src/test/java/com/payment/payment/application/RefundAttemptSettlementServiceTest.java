package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderStatus;
import com.payment.channelgateway.infra.persistence.InMemoryChannelOrderRepository;
import org.junit.jupiter.api.Test;

/**
 * 退款尝试收敛测试（fix：UNKNOWN 尝试行随权威结果收敛，不再永久滞留）。纯 JUnit，无 Spring 上下文。
 */
class RefundAttemptSettlementServiceTest {

    private final InMemoryChannelOrderRepository attempts = new InMemoryChannelOrderRepository();
    private final RefundAttemptSettlementService service = new RefundAttemptSettlementService(attempts);

    private ChannelOrder unknownRefundAttempt(String paymentNo, String channelReference) {
        ChannelOrder attempt = ChannelOrder.refundAttempt(paymentNo, "mock", 100, "CNY");
        attempt.accept(channelReference);
        attempt.markUnknown("channel refund accepted in-flight");
        return attempts.save(attempt);
    }

    @Test
    void convergeByChannelReferenceMovesUnknownAttemptToSucceeded() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.success("mock-refund-ref-1"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(ChannelOrderStatus.SUCCEEDED);
    }

    @Test
    void convergeByChannelReferenceMovesUnknownAttemptToFailed() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1",
                ChannelResult.businessFailure("mock-refund-ref-1", "channel declined"));

        ChannelOrder attempt = attempts.findByPaymentNo("PM-1").get(0);
        assertThat(attempt.getStatus()).isEqualTo(ChannelOrderStatus.FAILED);
        assertThat(attempt.getFailureReason()).isEqualTo("channel declined");
    }

    @Test
    void convergeWithoutReferenceFallsBackToLatestNonTerminalAttempt() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");
        ChannelOrder latest = unknownRefundAttempt("PM-1", "mock-refund-ref-2");

        service.convergeRefundAttempt("PM-1", null, ChannelResult.success(null));

        assertThat(latest.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
        assertThat(attempts.findByPaymentNo("PM-1").stream()
                .filter(a -> "mock-refund-ref-1".equals(a.getChannelReference()))
                .findFirst().orElseThrow().getStatus())
                .isEqualTo(ChannelOrderStatus.UNKNOWN); // 第一条不受影响
    }

    @Test
    void convergeUnknownOutcomeKeepsAttemptNonTerminal() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.businessUnknown("still unknown"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(ChannelOrderStatus.UNKNOWN);
    }

    @Test
    void convergeRepeatedResultIsAbsorbedByTerminalState() {
        unknownRefundAttempt("PM-1", "mock-refund-ref-1");

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.success("mock-refund-ref-1"));
        // 重复回调：终态吸收，状态不变
        service.convergeRefundAttempt("PM-1", "mock-refund-ref-1", ChannelResult.success("mock-refund-ref-1"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(ChannelOrderStatus.SUCCEEDED);
    }

    @Test
    void unmatchedReferenceAdoptsSoleUnconvergedAttemptAndBackfillsReference() {
        // 异步受理渠道未返回引用（channel_reference NULL），回调带引用来收敛：
        // 唯一在途退款尝试归属无歧义 → adopt + 回填引用
        unknownRefundAttempt("PM-1", null);

        service.convergeRefundAttempt("PM-1", "mock-refund-ref-cb",
                ChannelResult.businessFailure("mock-refund-ref-cb", "channel declined"));

        ChannelOrder attempt = attempts.findByPaymentNo("PM-1").get(0);
        assertThat(attempt.getStatus()).isEqualTo(ChannelOrderStatus.FAILED);
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
                .allMatch(a -> a.getStatus() == ChannelOrderStatus.UNKNOWN))
                .isTrue();
    }

    @Test
    void convergeIgnoresPaymentTypeAttempts() {
        // PAYMENT 尝试行不受退款收敛影响
        ChannelOrder paymentAttempt = new ChannelOrder("PM-1", "mock", 0, 100, "CNY");
        paymentAttempt.accept("mock-pay-ref-1");
        paymentAttempt.markUnknown("pay accepted in-flight");
        attempts.save(paymentAttempt);

        service.convergeRefundAttempt("PM-1", "mock-pay-ref-1", ChannelResult.success("mock-pay-ref-1"));

        assertThat(attempts.findByPaymentNo("PM-1").get(0).getStatus())
                .isEqualTo(ChannelOrderStatus.UNKNOWN);
    }
}
