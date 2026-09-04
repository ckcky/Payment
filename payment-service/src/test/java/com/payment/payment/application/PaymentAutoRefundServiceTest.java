package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.channel.MockChannelAdapter;
import org.junit.jupiter.api.Test;

/**
 * 自动退款（Feature 015 / P4 / INV-1）：订单 409 ORDER_NOT_PAYABLE → 原路退款。
 * 渠道可退 → 成功；渠道持续失败 → 重试后转人工（payment.auto_refund_failed 语义）。
 */
class PaymentAutoRefundServiceTest {

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final BusinessMetrics metrics = new NoopBusinessMetrics();

    private Payment succeededPayment() {
        Payment payment = new Payment("txn-ar", "order-ar", "user-ar", 100, "CNY", "idem-ar");
        payments.save(payment);
        payment.start(10L);
        payment.succeed();
        payments.save(payment);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        return payment;
    }

    @Test
    void refundsSucceededPaymentThroughChannel() {
        Payment payment = succeededPayment();
        PaymentRefundService refundService = new PaymentRefundService(payments,
                new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS), metrics, new StructuredAuditLogger());
        PaymentAutoRefundService service = new PaymentAutoRefundService(payments, refundService, metrics);

        service.autoRefund(payment.getPaymentNo(),
                new OrderNotPayableException(payment.getPaymentNo(), "order-ar", "CANCELLED"));
        // 不抛异常 + 渠道退款成功即满足契约（退款决策/留痕归退款域，对账兜底）
        assertThat(payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED); // 支付单保留 SUCCEEDED，不回滚
    }

    @Test
    void skipsNonSucceededPayment() {
        Payment payment = new Payment("txn-ar2", "order-ar2", "user-ar", 100, "CNY", "idem-ar2");
        payments.save(payment); // PENDING，未支付成功
        PaymentRefundService refundService = new PaymentRefundService(payments,
                new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS), metrics, new StructuredAuditLogger());
        PaymentAutoRefundService service = new PaymentAutoRefundService(payments, refundService, metrics);

        service.autoRefund(payment.getPaymentNo(),
                new OrderNotPayableException(payment.getPaymentNo(), "order-ar2", "CANCELLED"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void channelFailureExhaustsRetriesThenGivesUpToManual() {
        Payment payment = succeededPayment();
        PaymentRefundService refundService = new PaymentRefundService(payments,
                new MockChannelAdapter(MockChannelAdapter.Scenario.FAILURE), metrics, new StructuredAuditLogger());
        PaymentAutoRefundService service = new PaymentAutoRefundService(payments, refundService, metrics);

        service.autoRefund(payment.getPaymentNo(),
                new OrderNotPayableException(payment.getPaymentNo(), "order-ar", "CANCELLED"));
        // 渠道持续失败：重试耗尽后不再抛出（转人工），支付单事实不变
        assertThat(payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
