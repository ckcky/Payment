package com.payment.payment.integration;

import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未知支付收敛集成测试（T026）：超时进入 UNKNOWN、权威结果收敛、只触发一次履约 RPC。
 */
class PaymentUnknownResolutionTest {

    private final PaymentTestStack stack = new PaymentTestStack();

    @Test
    void timeoutThenResolveSuccessConvergesOnce() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.events).hasSize(1); // PaymentUnknown

        boolean resolved = stack.resolution.resolve(payment.getId(), ChannelResult.success("authoritative"));
        assertThat(resolved).isTrue();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.events).hasSize(2); // + PaymentSucceeded（只触发一次履约）

        boolean again = stack.resolution.resolve(payment.getId(), ChannelResult.success("authoritative"));
        assertThat(again).isFalse();
        assertThat(stack.events).hasSize(2); // 不再发布第二个 PaymentSucceeded
    }

    @Test
    void resolveNonUnknownPaymentIsNoOp() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        boolean resolved = stack.resolution.resolve(payment.getId(), ChannelResult.success("x"));
        assertThat(resolved).isFalse();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
