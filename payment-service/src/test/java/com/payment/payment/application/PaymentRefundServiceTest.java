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
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.channelgateway.infra.MockChannelAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款尝试编排测试（T053）：金额查询与退款尝试的守卫/映射，纯 JUnit，无 Spring 上下文。
 */
class PaymentRefundServiceTest {

    private final InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();

    private PaymentRefundService service(MockChannelAdapter channel) {
        return new PaymentRefundService(repository, attempts, channel, new NoopBusinessMetrics(),
                new StructuredAuditLogger());
    }

    private Payment succeededPayment() {
        Payment payment = new Payment("txn-1", "order-1", "user-1", 100, "CNY", "idem-1");
        payment.start(1L);
        payment.succeed();
        Payment saved = repository.save(payment);
        // Feature 028 / FR-005 / INV-6：退款渠道取自被退支付单的**生效支付渠道**记录
        // （attempt_type=PAYMENT 且 SUCCEEDED 的 channel_code），所以必须先落这条支付尝试。
        // 用 id=1 会被 INNER 内存仓储的 idGen 自增覆盖，故不显式给 id。
        PaymentAttempt paid = PaymentAttempt.rehydrate(null, saved.getPaymentNo(), "MOCK", 0,
                java.time.Instant.now(), java.time.Instant.now(), "mock-ref", PaymentAttemptStatus.SUCCEEDED,
                null, null, 0, PaymentAttempt.TYPE_PAYMENT, 100, "CNY");
        attempts.save(paid);
        return saved;
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
                "RF-1", payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), 50, "CNY", "reason", "rk-1"));

        assertThat(response.refundNo()).isEqualTo("RF-1");
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.channelReference()).isNotNull();
        // Feature 016 / FR-017 ②：退款渠道尝试落库（attempt_type=REFUND，channel_reference=渠道退款流水号）
        // Feature 028：PAYMENT 尝试（生效支付渠道）之外新增 1 条 REFUND 尝试，共 2 条
        assertThat(attempts.findByPaymentNo(payment.getPaymentNo())).hasSize(2);
        PaymentAttempt attempt = attempts.findByPaymentNo(payment.getPaymentNo()).stream()
                .filter(a -> PaymentAttempt.TYPE_REFUND.equals(a.getAttemptType()))
                .findFirst()
                .orElseThrow();
        assertThat(attempt.getAttemptType()).isEqualTo(PaymentAttempt.TYPE_REFUND);
        assertThat(attempt.getChannelReference()).isEqualTo(response.channelReference());
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        // spec 018 / US1 / D2：REFUND 尝试记所属支付单金额（100），而非退款金额（50）
        assertThat(attempt.getAmountMinor()).isEqualTo(100);
        assertThat(attempt.getCurrencyCode()).isEqualTo("CNY");
    }

    @Test
    void refundTimeoutReturnsUnknown() {
        Payment payment = succeededPayment();
        PaymentRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));

        RefundAttemptResponse response = service.refund(new RefundAttemptRequest(
                "RF-1", payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), 100, "CNY", "reason", "rk-1"));

        assertThat(response.status()).isEqualTo("UNKNOWN");
    }

    @Test
    void refundOnNonSucceededPaymentThrowsStateTransitionViolation() {
        Payment payment = new Payment("txn-1", "order-1", "user-1", 100, "CNY", "idem-1");
        repository.save(payment);
        PaymentRefundService service = service(new MockChannelAdapter());

        assertThatThrownBy(() -> service.refund(new RefundAttemptRequest(
                "RF-1", payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), 100, "CNY", "reason", "rk-1")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION);
    }

    // 退款尝试收敛（fix）的单元测试见 RefundAttemptSettlementServiceTest。
}
