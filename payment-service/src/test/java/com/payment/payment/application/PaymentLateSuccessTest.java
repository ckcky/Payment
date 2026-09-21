package com.payment.payment.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单取消后渠道迟到成功的追回（spec 034 §6.3 C-23 / H-034-3，TT-9）：
 * 渠道 SUCCESS 到达时 Payment 已 CLOSED——终态吸收不变（Payment 不复活，R-3），
 * 但「渠道已收款」事实必须告知 order：{@code payment.late_success_on_closed} 计数 +
 * {@code late=true} 通知（order 既有 ORDER_NOT_PAYABLE surplus 分支自动原路退回）。
 *
 * <p>late 路径不产生 PAYMENT_CAPTURE 记账（capture 时点已过，plan §4）；
 * 已 SUCCEEDED 支付的重复 SUCCESS 通知不误报 late（终态吸收既有语义）。</p>
 */
class PaymentLateSuccessTest {

    private final PaymentTestStack stack = new PaymentTestStack();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** TIMEOUT 渠道 → UNKNOWN 支付（终态前可被 closeByOrderCancelled 关闭）。 */
    private Payment unknownPayment() {
        Payment payment = stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(stack.command("k-late"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        return payment;
    }

    private PaymentResultProcessor processor() {
        return new PaymentResultProcessor(stack.payments, stack.attempts, stack.order,
                facts -> {
                },
                new MicrometerBusinessMetrics(registry),
                new StructuredAuditLogger());
    }

    @Test
    void lateSuccessOnClosedIsCountedAndNotifiedWithLateFlag() {
        Payment payment = unknownPayment();
        // 订单取消 → 关闭支付（spec 029 / FR-301 既有路径）
        payment.closeByOrderCancelled();
        stack.payments.save(payment);

        int notifiedBefore = stack.order.succeededRequests.size();
        boolean changed = processor().applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-late"));

        // 终态吸收：Payment 不复活（R-3），apply 返回 false
        assertThat(changed).isFalse();
        assertThat(stack.payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CLOSED);
        // 追回信号：专用计数（cause=CANCELLED）+ late=true 通知照发
        assertThat(registry.get("payment.late_success_on_closed").tag("cause", "CANCELLED")
                .counter().count()).isEqualTo(1.0);
        assertThat(stack.order.succeededRequests).hasSize(notifiedBefore + 1);
        PaymentSucceededRequest request = stack.order.succeededRequests.getLast();
        assertThat(request.late()).isTrue();
        assertThat(request.paymentNo()).isEqualTo(payment.getPaymentNo());
    }

    @Test
    void duplicateSuccessOnSucceededIsNotLate() {
        Payment payment = unknownPayment();
        processor().applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-first"));
        assertThat(stack.payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);

        int notifiedBefore = stack.order.succeededRequests.size();
        boolean changed = processor().applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-replay"));

        // 重复 SUCCESS：终态吸收，无通知、无 late 误报
        assertThat(changed).isFalse();
        assertThat(stack.order.succeededRequests).hasSize(notifiedBefore);
        assertThat(registry.find("payment.late_success_on_closed").counter()).isNull();
    }
}
