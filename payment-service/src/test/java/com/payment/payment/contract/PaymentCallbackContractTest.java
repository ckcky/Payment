package com.payment.payment.contract;

import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付回调契约测试（T025）：成功、失败、重复、延迟回调与终态保护。
 */
class PaymentCallbackContractTest {

    private final PaymentTestStack stack = new PaymentTestStack();

    @Test
    void callbackSuccessConvergesUnknownPayment() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        boolean changed = stack.callback.handleCallback(payment.getId(), ChannelResult.success("ref-cb"));
        assertThat(changed).isTrue();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void duplicateCallbackDoesNotPublishTwice() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));

        stack.callback.handleCallback(payment.getId(), ChannelResult.success("ref-cb"));
        int eventsAfterFirst = stack.events.size();

        boolean changed = stack.callback.handleCallback(payment.getId(), ChannelResult.success("ref-cb"));
        assertThat(changed).isFalse();
        assertThat(stack.events).hasSize(eventsAfterFirst);
    }

    @Test
    void lateFailureCallbackDoesNotOverwriteSuccess() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        boolean changed = stack.callback.handleCallback(
                payment.getId(), ChannelResult.failure("ref", "late decline"));
        assertThat(changed).isFalse();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void unknownCallbackAfterUnknownStaysUnknownWithoutNewEvent() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        int eventsAfterFirst = stack.events.size();

        boolean changed = stack.callback.handleCallback(payment.getId(), ChannelResult.unknown("still unknown"));
        assertThat(changed).isFalse();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.events).hasSize(eventsAfterFirst);
    }
}
