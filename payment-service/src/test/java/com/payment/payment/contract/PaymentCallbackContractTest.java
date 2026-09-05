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
 *
 * <p>Feature 016（ADR-0054）：payment 业务侧扇出仅 order（{@code stack.order}）；
 * 履约改由 order 层驱动，本测试以 order 通知次数对称断言「成功恰一次、失败/未知零次」。</p>
 */
class PaymentCallbackContractTest {

    private final PaymentTestStack stack = new PaymentTestStack();

    @Test
    void callbackSuccessConvergesUnknownPayment() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        boolean changed = stack.callback.handleCallback(payment.getPaymentNo(), ChannelResult.success("ref-cb"));
        assertThat(changed).isTrue();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        // T023：SUCCESS 通知 order 恰好 1 次
        assertThat(stack.order.succeededRequests).hasSize(1);
    }

    @Test
    void duplicateCallbackDoesNotPublishTwice() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));

        stack.callback.handleCallback(payment.getPaymentNo(), ChannelResult.success("ref-cb"));
        int requestsAfterFirst = stack.order.succeededRequests.size();

        boolean changed = stack.callback.handleCallback(payment.getPaymentNo(), ChannelResult.success("ref-cb"));
        assertThat(changed).isFalse();
        assertThat(stack.order.succeededRequests).hasSize(requestsAfterFirst);
    }

    @Test
    void lateFailureCallbackDoesNotOverwriteSuccess() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        boolean changed = stack.callback.handleCallback(
                payment.getPaymentNo(), ChannelResult.businessFailure("ref", "late decline"));
        assertThat(changed).isFalse();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        // T023 + Feature 016（FR-001）：同步 charge 成功已通知 order 恰一次；
        // 迟到失败不产生二次成功事实（changed=false 不再通知）
        assertThat(stack.order.succeededRequests).hasSize(1);
    }

    @Test
    void unknownCallbackAfterUnknownStaysUnknownWithoutNewEvent() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        int requestsAfterFirst = stack.order.succeededRequests.size();

        boolean changed = stack.callback.handleCallback(payment.getPaymentNo(), ChannelResult.businessUnknown("still unknown"));
        assertThat(changed).isFalse();
        assertThat(service.getPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.order.succeededRequests).hasSize(requestsAfterFirst);
        // T023：UNKNOWN 不通知 order
        assertThat(stack.order.succeededRequests).isEmpty();
    }
}
