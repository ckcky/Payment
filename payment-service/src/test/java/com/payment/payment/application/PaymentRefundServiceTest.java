package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.channel.MockChannelAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款尝试编排测试（T053）：金额查询与退款尝试的守卫/映射，纯 JUnit，无 Spring 上下文。
 */
class PaymentRefundServiceTest {

    private final InMemoryPaymentRepository repository = new InMemoryPaymentRepository();

    private PaymentRefundService service(MockChannelAdapter channel) {
        return new PaymentRefundService(repository, channel, new NoopBusinessMetrics(),
                new StructuredAuditLogger());
    }

    private Payment succeededPayment() {
        Payment payment = new Payment("txn-1", "order-1", "user-1", 100, "CNY", "idem-1");
        payment.start(1L);
        payment.succeed();
        return repository.save(payment);
    }

    @Test
    void queryAmountUnknownIdThrowsNotFound() {
        PaymentRefundService service = service(new MockChannelAdapter());
        assertThatThrownBy(() -> service.queryAmount(new PaymentAmountQueryRequest("PM-999")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void queryAmountReturnsPaidAmountAndStatus() {
        Payment payment = succeededPayment();
        PaymentRefundService service = service(new MockChannelAdapter());

        PaymentAmountQueryResponse response = service.queryAmount(new PaymentAmountQueryRequest(payment.getPaymentNo()));

        assertThat(response.paymentNo()).isEqualTo(payment.getPaymentNo());
        assertThat(response.paidAmountMinor()).isEqualTo(100);
        assertThat(response.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void refundSuccessReturnsSucceededAndChannelReference() {
        Payment payment = succeededPayment();
        PaymentRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS));

        RefundAttemptResponse response = service.refund(new RefundAttemptRequest(
                1L, payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), 100, "CNY", "reason", "rk-1"));

        assertThat(response.refundId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.channelReference()).isNotNull();
    }

    @Test
    void refundTimeoutReturnsUnknown() {
        Payment payment = succeededPayment();
        PaymentRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));

        RefundAttemptResponse response = service.refund(new RefundAttemptRequest(
                1L, payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), 100, "CNY", "reason", "rk-1"));

        assertThat(response.status()).isEqualTo("UNKNOWN");
    }

    @Test
    void refundOnNonSucceededPaymentThrowsStateTransitionViolation() {
        Payment payment = new Payment("txn-1", "order-1", "user-1", 100, "CNY", "idem-1");
        repository.save(payment);
        PaymentRefundService service = service(new MockChannelAdapter());

        assertThatThrownBy(() -> service.refund(new RefundAttemptRequest(
                1L, payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), 100, "CNY", "reason", "rk-1")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION);
    }
}
