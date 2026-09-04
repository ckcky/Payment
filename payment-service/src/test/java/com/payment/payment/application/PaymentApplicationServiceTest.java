package com.payment.payment.application;

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
    void successTriggersFulfillmentOnce() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.fulfillment.succeededRequests).hasSize(1);
        assertThat(stack.fulfillment.succeededRequests.get(0).orderNo()).isEqualTo("order-1");
        assertThat(stack.fulfillment.succeededRequests.get(0).transactionId()).isEqualTo("txn-1");
        assertThat(stack.fulfillment.succeededRequests.get(0).amountMinor()).isEqualTo(100);
    }

    @Test
    void failureDoesNotTriggerFulfillment() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.FAILURE));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stack.fulfillment.succeededRequests).isEmpty();
    }

    @Test
    void timeoutDoesNotTriggerFulfillment() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.fulfillment.succeededRequests).isEmpty();
    }

    @Test
    void duplicateIdempotencyKeyReturnsSamePaymentWithoutNewAttemptOrFulfillment() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment first = service.createPaymentIntent(stack.command("dup"));
        Payment second = service.createPaymentIntent(stack.command("dup"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stack.attempts.findByPaymentNo(first.getPaymentNo())).hasSize(1);
        assertThat(stack.fulfillment.succeededRequests).hasSize(1);
    }
}
