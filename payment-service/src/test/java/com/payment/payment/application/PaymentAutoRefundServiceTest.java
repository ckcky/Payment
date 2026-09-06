package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundPostProcessOrchestrator;
import com.payment.refund.domain.Refund;
import com.payment.refund.infra.client.LocalPaymentRefundGateway;
import com.payment.refund.infra.InMemoryRefundRepository;
import com.payment.refund.support.RefundTestStack;
import org.junit.jupiter.api.Test;

/**
 * 自动退款执行器测试（Feature 016 / ADR-0054 / FR-017）：
 * order transaction 层以 {@code transactionNo + paymentNo} 下发命令 →
 * payment 走退款域完整生命周期（生成 refundNo → 落退款渠道尝试记录 → 渠道退款三态收敛）。
 * 幂等键 {@code autorefund:{transactionNo}:{paymentNo}} 重复触发吸收。
 */
class PaymentAutoRefundServiceTest {

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository paymentAttempts = new InMemoryPaymentAttemptRepository();
    private final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    private final RefundTestStack refundFakes = new RefundTestStack();
    private final BusinessMetrics metrics = new NoopBusinessMetrics();

    private Payment succeededPayment() {
        Payment payment = new Payment("TXN-AR", "order-ar", "user-ar", 100, "CNY", "idem-ar");
        payments.save(payment);
        payment.start(10L);
        payment.succeed();
        payments.save(payment);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        return payment;
    }

    private PaymentAutoRefundService service(MockChannelAdapter channel) {
        PaymentRefundService paymentRefundService = new PaymentRefundService(payments, paymentAttempts,
                channel, metrics, new StructuredAuditLogger());
        RefundPostProcessOrchestrator orchestrator = new RefundPostProcessOrchestrator(
                refundFakes.fulfillment, refundFakes.entitlement, refundFakes.ledger, refundFakes.attempts,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        RefundApplicationService refundApplicationService = new RefundApplicationService(
                refunds, new LocalPaymentRefundGateway(paymentRefundService), orchestrator,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        return new PaymentAutoRefundService(payments, refundApplicationService, metrics);
    }

    private RefundCommandRequest command(String paymentNo) {
        return new RefundCommandRequest("TXRF-AR-1", "TXN-AR", paymentNo, "order-ar", "user-ar", 100, "CNY");
    }

    @Test
    void refundCommandCreatesRefundAndSucceedsThroughChannel() {
        Payment payment = succeededPayment();
        PaymentAutoRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS));

        RefundCommandResponse response = service.refundByOrder(command(payment.getPaymentNo()));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        // 退款域落单（refunds 表）+ 渠道尝试记录（payment_attempts，attempt_type=REFUND）
        assertThat(refunds.findByPaymentNo(payment.getPaymentNo())).hasSize(1);
        Refund refund = refunds.findByPaymentNo(payment.getPaymentNo()).get(0);
        assertThat(refund.getIdempotencyKey()).isEqualTo("autorefund:TXN-AR:" + payment.getPaymentNo());
        assertThat(paymentAttempts.findByPaymentNo(payment.getPaymentNo())).hasSize(1);
        assertThat(paymentAttempts.findByPaymentNo(payment.getPaymentNo()).get(0).getAttemptType())
                .isEqualTo(PaymentAttempt.TYPE_REFUND);
        // 支付单保留 SUCCEEDED，不回滚
        assertThat(payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void duplicateCommandIsAbsorbedByIdempotencyKey() {
        Payment payment = succeededPayment();
        PaymentAutoRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS));

        RefundCommandResponse first = service.refundByOrder(command(payment.getPaymentNo()));
        RefundCommandResponse second = service.refundByOrder(command(payment.getPaymentNo()));

        assertThat(second.refundNo()).isEqualTo(first.refundNo());
        assertThat(refunds.findByPaymentNo(payment.getPaymentNo())).hasSize(1);
    }

    @Test
    void skipsNonSucceededPayment() {
        Payment payment = new Payment("TXN-AR2", "order-ar2", "user-ar", 100, "CNY", "idem-ar2");
        payments.save(payment); // PENDING，未支付成功
        PaymentAutoRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS));

        assertThatThrownBy(() -> service.refundByOrder(command(payment.getPaymentNo())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION);
        assertThat(refunds.findByPaymentNo(payment.getPaymentNo())).isEmpty();
    }

    @Test
    void channelBusinessFailureYieldsFailedRefundWithoutRollback() {
        Payment payment = succeededPayment();
        PaymentAutoRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.FAILURE));

        RefundCommandResponse response = service.refundByOrder(command(payment.getPaymentNo()));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED); // 支付单事实不变，失败转人工/对账兜底
    }
}
