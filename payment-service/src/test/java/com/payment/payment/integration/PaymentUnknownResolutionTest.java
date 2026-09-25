package com.payment.payment.integration;

import com.payment.payment.application.PaymentApplicationService;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.channelgateway.infra.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未知支付收敛集成测试（T026）：超时进入 UNKNOWN、权威结果收敛、只触发一次履约 RPC。
 *
 * <p>spec 041：{@code resolve} 返回<b>收敛后的支付单</b>（不再是 boolean），
 * 故「是否再收敛一次」改由「order 通知次数」与支付单状态共同断言。</p>
 */
class PaymentUnknownResolutionTest {

    private final PaymentTestStack stack = new PaymentTestStack();

    @Test
    void timeoutThenResolveSuccessConvergesOnce() {
        PaymentApplicationService service =
                stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT));
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.order.succeededRequests).isEmpty(); // UNKNOWN 不通知 order

        Payment resolved = stack.resolution.resolve(payment.getPaymentNo(), ChannelResult.success("authoritative"));
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(service.getPaymentByNo(payment.getPaymentNo()).getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.order.succeededRequests).hasSize(1); // 收敛为成功，通知 order 一次

        Payment again = stack.resolution.resolve(payment.getPaymentNo(), ChannelResult.success("authoritative"));
        assertThat(again.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.order.succeededRequests).hasSize(1); // 幂等：不再第二次通知 order
    }

    @Test
    void resolveNonUnknownPaymentIsNoOp() {
        PaymentApplicationService service = stack.appService(new MockChannelAdapter());
        Payment payment = service.createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        Payment resolved = stack.resolution.resolve(payment.getPaymentNo(), ChannelResult.success("x"));
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stack.order.succeededRequests).hasSize(1); // 未产生新的成功事实
    }
}
