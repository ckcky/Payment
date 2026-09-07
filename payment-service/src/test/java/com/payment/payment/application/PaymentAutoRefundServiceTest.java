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
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundResultProcessor;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.infra.client.LocalPaymentRefundGateway;
import com.payment.refund.infra.InMemoryRefundRepository;
import com.payment.refund.support.RefundTestStack;
import org.junit.jupiter.api.Test;

/**
 * 自动退款执行器测试（Feature 016 / ADR-0054 / spec 019 T107/T110）：
 * order transaction 层以 TXRF 下发命令 → payment 生成 PMRF 执行单走退款域完整生命周期。
 * 幂等键 = transactionRefundNo（TXRF）：重复触发幂等吸收、可重入回放。
 * 异步受理模式：命令响应 PROCESSING，终态经渠道回调 + RefundResultNotification 通知 order 收口。
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
        RefundResultProcessor processor = new RefundResultProcessor(
                refunds, refundFakes.order, refundFakes.ledger,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        RefundApplicationService refundApplicationService = new RefundApplicationService(
                refunds, new LocalPaymentRefundGateway(paymentRefundService), processor,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        return new PaymentAutoRefundService(payments, refundApplicationService, metrics);
    }

    private RefundCommandRequest command(String paymentNo) {
        return new RefundCommandRequest("TXRF-AR-1", "TXN-AR", paymentNo, "order-ar", "user-ar", 100, "CNY");
    }

    @Test
    void refundCommandCreatesPmrfRefundAndSucceedsThroughChannel() {
        Payment payment = succeededPayment();
        PaymentAutoRefundService service = service(new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS));

        RefundCommandResponse response = service.refundByOrder(command(payment.getPaymentNo()));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        // 双层单号：支付层执行单自生成 PMRF，幂等键 = TXRF（spec 019 / T107）
        assertThat(response.refundNo()).startsWith("PMRF");
        assertThat(refunds.findByPaymentNo(payment.getPaymentNo())).hasSize(1);
        Refund refund = refunds.findByPaymentNo(payment.getPaymentNo()).get(0);
        assertThat(refund.getIdempotencyKey()).isEqualTo("TXRF-AR-1");
        assertThat(refund.getTransactionRefundNo()).isEqualTo("TXRF-AR-1");
        assertThat(refund.getTransactionNo()).isEqualTo("TXN-AR");
        assertThat(paymentAttempts.findByPaymentNo(payment.getPaymentNo())).hasSize(1);
        assertThat(paymentAttempts.findByPaymentNo(payment.getPaymentNo()).get(0).getAttemptType())
                .isEqualTo(PaymentAttempt.TYPE_REFUND);
        // 支付单保留 SUCCEEDED，不回滚
        assertThat(payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        // 记账幂等键 = PMRF（"REFUND:" 前缀由出站网关添加，T109 修双重前缀）
        assertThat(refundFakes.ledger.postingKeys).hasSize(1);
        assertThat(refundFakes.ledger.postingKeys.get(0)).startsWith("PMRF");
        // 终态通知 order（TXRF+PMRF 双号，ADR-0067）
        assertThat(refundFakes.order.refundNotifications).hasSize(1);
        assertThat(refundFakes.order.refundNotifications.get(0).transactionRefundNo()).isEqualTo("TXRF-AR-1");
    }

    @Test
    void duplicateCommandReplaysSameExecutionRefund() {
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
        // 失败终态也通知 order（order 侧推进退款单终态，不作资金累加）
        assertThat(refundFakes.order.refundNotifications).hasSize(1);
        assertThat(refundFakes.order.refundNotifications.get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void asyncAcceptReturnsProcessingAndCallbackConvergesWithOrderNotify() throws Exception {
        Payment payment = succeededPayment();
        MockChannelAdapter channel = new MockChannelAdapter(
                MockChannelAdapter.Scenario.SUCCESS, 1500L, true, 10L);
        PaymentRefundService paymentRefundService = new PaymentRefundService(payments, paymentAttempts,
                channel, metrics, new StructuredAuditLogger());
        RefundResultProcessor processor = new RefundResultProcessor(
                refunds, refundFakes.order, refundFakes.ledger,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        PaymentAutoRefundService service = new PaymentAutoRefundService(payments,
                new RefundApplicationService(refunds, new LocalPaymentRefundGateway(paymentRefundService),
                        processor, new NoopBusinessMetrics(), new StructuredAuditLogger()),
                metrics);
        // 注入监听：模拟渠道延迟推送权威结果（真实渠道走 HTTP 回调端点，同一收敛路径）
        channel.setRefundResultListener(new com.payment.refund.application.MockRefundResultBridge(
                new com.payment.refund.application.RefundRpcCallbackService(refunds, processor)));

        RefundCommandResponse response = service.refundByOrder(command(payment.getPaymentNo()));

        // 受理在途：命令响应 PROCESSING（order 侧退款单保持 PROCESSING，等回调收口）
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.refundNo()).startsWith("PMRF");

        // 渠道异步推送 → 退款收敛 SUCCEEDED + 记账 + 通知 order（轮询等待异步推送落地）
        Refund refund = null;
        for (int i = 0; i < 100 && (refund == null || refund.getStatus() != RefundStatus.SUCCEEDED); i++) {
            Thread.sleep(20L);
            refund = refunds.findByRefundNo(response.refundNo()).orElse(null);
        }
        assertThat(refund).isNotNull();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refundFakes.ledger.postingKeys).hasSize(1);
        assertThat(refundFakes.order.refundNotifications).hasSize(1);
        assertThat(refundFakes.order.refundNotifications.get(0).transactionRefundNo()).isEqualTo("TXRF-AR-1");
        assertThat(refundFakes.order.refundNotifications.get(0).paymentRefundNo()).isEqualTo(response.refundNo());
    }
}
