package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code createPaymentIntent(cmd, deferChannel=true)} 收银台路径（ADR-0048 修订版）。
 *
 * <p>契约：defer 模式跳过渠道内联同步调用（渠道绝不能被触达），Payment 停留 PROCESSING
 * 等待收银台回调；幂等重复仍按既有语义返回首次结果。</p>
 */
class PaymentDeferredChannelTest {

    private final PaymentTestStack stack = new PaymentTestStack();

    /** 一旦被调用即失败的渠道：defer 模式下绝不允许触达。 */
    private static final PaymentChannel NEVER_CALLED = new PaymentChannel() {
        @Override
        public ChannelResult charge(ChargeRequest request) {
            throw new AssertionError("deferred mode must not call the channel: " + request.paymentId());
        }

        @Override
        public ChannelResult refund(com.payment.payment.application.channel.RefundRequest request) {
            throw new AssertionError("deferred mode must not call the channel");
        }

        @Override
        public ChannelResult queryStatus(com.payment.payment.application.channel.QueryStatusRequest request) {
            throw new AssertionError("deferred mode must not call the channel");
        }
    };

    @Test
    @DisplayName("defer=true：不调渠道，Payment 停留 PROCESSING 等收银台回调")
    void deferredPaymentStaysProcessingWithoutChannelCall() {
        PaymentApplicationService service = stack.appService(NEVER_CALLED);

        Payment payment = service.createPaymentIntent(stack.command("defer-idem-1"), true);

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(stack.fulfillment.succeededRequests)
                .as("未收到渠道结果，不得履约").isEmpty();
    }

    @Test
    @DisplayName("defer=true 的幂等重复：仍返回首次结果，不重复创建")
    void deferredDuplicateReturnsFirstResult() {
        PaymentApplicationService service = stack.appService(NEVER_CALLED);

        Payment first = service.createPaymentIntent(stack.command("defer-idem-2"), true);
        Payment replay = service.createPaymentIntent(stack.command("defer-idem-2"), true);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getStatus()).isEqualTo(first.getStatus());
    }

    @Test
    @DisplayName("defer=false（默认）：既有同步 charge 主链零变化（SUCCESS → SUCCEEDED + 履约）")
    void nonDeferredPathUnchanged() {
        PaymentChannel successChannel = new PaymentChannel() {
            @Override
            public ChannelResult charge(ChargeRequest request) {
                return ChannelResult.success("mock-ref-ok");
            }

            @Override
            public ChannelResult refund(com.payment.payment.application.channel.RefundRequest request) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public ChannelResult queryStatus(com.payment.payment.application.channel.QueryStatusRequest request) {
                return ChannelResult.businessUnknown("not used in this test");
            }
        };
        PaymentApplicationService service = stack.appService(successChannel);

        Payment payment = service.createPaymentIntent(stack.command("sync-idem-1"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.fulfillment.succeededRequests).hasSize(1);
    }
}
