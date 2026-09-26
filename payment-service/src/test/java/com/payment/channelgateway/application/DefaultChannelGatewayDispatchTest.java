package com.payment.channelgateway.application;

import com.payment.common.dto.channel.PayCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道网关门面的扣款派发（spec 041 / FR-021）。
 *
 * <p>核心断言沿用改造前 {@code PaymentDeferredChannelTest} 的那一条：
 * <b>被策略截住时，渠道实现 MUST NOT 被调用</b>。改造前这条断言挂在 payment 侧
 * （靠 {@code defer} 布尔短路），改造后挂在渠道网关侧（靠派发策略在解析之前裁决）——
 * 断言语义不变，只是回到了它本该在的位置。</p>
 */
class DefaultChannelGatewayDispatchTest {

    private static final String CODE = "MOCK";

    /** 一旦被调用即失败的渠道：被截住时绝不允许触达。 */
    private static final PaymentChannel NEVER_CALLED = new PaymentChannel() {
        @Override
        public String channelCode() {
            return CODE;
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            throw new AssertionError("deferred dispatch must not call the channel: " + request.paymentNo());
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            throw new AssertionError("deferred dispatch must not call the channel");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            throw new AssertionError("deferred dispatch must not call the channel");
        }
    };

    private static ChargeRequest request() {
        return new ChargeRequest("PM-dispatch-1", 9900L, "CNY", CODE);
    }

    @Test
    @DisplayName("策略给出凭证 ⇒ 不触达渠道实现，返回「已受理、买家未付款」")
    void deferredDispatchNeverReachesChannel() {
        ChannelGateway gateway = new DefaultChannelGateway(new SingleChannelRegistry(NEVER_CALLED), null,
                req -> Optional.of(PayCredential.redirectUrl("http://localhost:8091/cashier?paymentNo=PM-dispatch-1", null)));

        ChannelResult result = gateway.pay(CODE, request());

        assertThat(result.hasCredential()).isTrue();
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(result.credential().payload()).contains("/cashier?");
    }

    @Test
    @DisplayName("策略不截住 ⇒ 正常解析渠道并调用")
    void nonDeferredDispatchCallsChannel() {
        PaymentChannel ok = new PaymentChannel() {
            @Override
            public String channelCode() {
                return CODE;
            }

            @Override
            public ChannelResult charge(ChargeRequest req) {
                return ChannelResult.success("ch-ok");
            }

            @Override
            public ChannelResult refund(RefundRequest request) {
                return ChannelResult.businessFailure(null, "unused");
            }

            @Override
            public ChannelResult queryStatus(QueryStatusRequest request) {
                return ChannelResult.businessUnknown("unused");
            }
        };
        ChannelGateway gateway = new DefaultChannelGateway(new SingleChannelRegistry(ok), null,
                req -> Optional.empty());

        assertThat(gateway.pay(CODE, request()).status()).isEqualTo(ChannelResult.Status.SUCCESS);
    }

    @Test
    @DisplayName("无派发策略（兼容构造）⇒ 行为与改造前逐字一致：正常调用渠道")
    void nullPolicyKeepsLegacyBehaviour() {
        PaymentChannel ok = new PaymentChannel() {
            @Override
            public String channelCode() {
                return CODE;
            }

            @Override
            public ChannelResult charge(ChargeRequest req) {
                return ChannelResult.success("ch-legacy");
            }

            @Override
            public ChannelResult refund(RefundRequest request) {
                return ChannelResult.businessFailure(null, "unused");
            }

            @Override
            public ChannelResult queryStatus(QueryStatusRequest request) {
                return ChannelResult.businessUnknown("unused");
            }
        };
        ChannelGateway gateway = new DefaultChannelGateway(new SingleChannelRegistry(ok), null);

        assertThat(gateway.pay(CODE, request()).status()).isEqualTo(ChannelResult.Status.SUCCESS);
    }
}
