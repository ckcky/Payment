package com.payment.channelgateway.infra.config;

import com.payment.channelgateway.application.ChargeRequest;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.dto.channel.PayCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示收银台派发策略（spec 041 / FR-021）。
 *
 * <p>本测试取代改造前的 {@code PaymentDeferredChannelTest} 中「defer = enabled && !sandbox」
 * 那部分断言——判断已从 payment 层（Controller）迁入渠道网关域，故测试也随之下沉。</p>
 */
class DemoCashierDispatchPolicyTest {

    private static MockCashierProperties props(boolean enabled) {
        MockCashierProperties props = new MockCashierProperties();
        props.setEnabled(enabled);
        props.setBaseUrl("http://localhost:8091");
        return props;
    }

    private static ChargeRequest request(String channelCode) {
        return new ChargeRequest("PM-test", 9900L, "CNY", channelCode,
                null, null, null, null, null, null, "order-9");
    }

    @Test
    @DisplayName("开关关闭（默认）⇒ 不截住，正常派发到渠道")
    void disabledPolicyNeverDefers() {
        assertThat(new DemoCashierDispatchPolicy(props(false)).deferredCredential(request("MOCK")))
                .isEmpty();
    }

    @Test
    @DisplayName("开关开启且非沙箱 ⇒ 给出收银台凭证")
    void enabledPolicyReturnsCashierCredential() {
        Optional<PayCredential> credential =
                new DemoCashierDispatchPolicy(props(true)).deferredCredential(request("ALIPAY"));

        assertThat(credential).isPresent();
        assertThat(credential.get().kind()).isEqualTo(PayCredential.Kind.REDIRECT_URL);
        assertThat(credential.get().payload()).isEqualTo(
                "http://localhost:8091/cashier?paymentNo=PM-test&orderNo=order-9"
                        + "&amountMinor=9900&currencyCode=CNY&channelCode=ALIPAY");
    }

    @Test
    @DisplayName("开关开启但处于沙箱染色 ⇒ 不截住（沙箱必须真调渠道才拿得到凭证，FR-167）")
    void sandboxIsNeverDeferred() {
        DemoCashierDispatchPolicy policy = new DemoCashierDispatchPolicy(props(true));

        Optional<PayCredential> credential = DyeContext.callWith(DyeMode.SANDBOX,
                () -> policy.deferredCredential(request("MOCK")));

        assertThat(credential).isEmpty();
    }

    @Test
    @DisplayName("渠道码缺失 ⇒ 回落 MOCK（与改造前 buildPayUrl 的兜底逐字一致）")
    void blankChannelCodeFallsBackToMock() {
        Optional<PayCredential> credential =
                new DemoCashierDispatchPolicy(props(true)).deferredCredential(request(null));

        assertThat(credential).isPresent();
        assertThat(credential.get().payload()).endsWith("&channelCode=MOCK");
    }
}
