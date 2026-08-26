package com.payment.payment.application;

import com.payment.common.dto.event.PaymentFailed;
import com.payment.common.dto.event.PaymentSucceeded;
import com.payment.common.dto.event.PaymentUnknown;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付意图创建测试（T037 + T018 幂等）：成功/失败/未知路径与幂等受理。
 */
class PaymentApplicationServiceTest {

    private final PaymentTestStack stack = new PaymentTestStack();

    @Test
    void successPublishesPaymentSucceeded() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.events).hasSize(1);
        assertThat(stack.events.get(0)).isInstanceOf(PaymentSucceeded.class);
    }

    @Test
    void failurePublishesPaymentFailed() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.FAILURE));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stack.events).hasSize(1);
        assertThat(stack.events.get(0)).isInstanceOf(PaymentFailed.class);
    }

    @Test
    void timeoutPublishesPaymentUnknown() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.events).hasSize(1);
        assertThat(stack.events.get(0)).isInstanceOf(PaymentUnknown.class);
    }

    @Test
    void duplicateIdempotencyKeyReturnsSamePaymentWithoutNewAttemptOrEvent() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment first = service.createPaymentIntent(stack.command("dup"));
        Payment second = service.createPaymentIntent(stack.command("dup"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stack.attempts.findByPaymentId(first.getId())).hasSize(1);
        assertThat(stack.events).hasSize(1);
    }
}
