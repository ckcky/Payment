package com.payment.channel.infra.plugins.stripe;

import com.payment.channel.application.ChannelResult;
import com.payment.channel.application.ChargeRequest;
import com.payment.channel.application.spi.ChannelPlugin;
import com.payment.channel.application.spi.ChannelPluginDescriptor;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.error.BizException;
import com.payment.common.dto.channel.Goods;
import com.payment.common.dto.channel.PaymentScene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stripe 渠道插件独立装配测试（spec 041 / T11 验收：每渠道独立装配测试；
 * 渠道插件化 / STRIPE-01~04 的结构钉）。
 *
 * <p>Stripe 此前<b>没有专属测试</b>（只有微信/支付宝/mock 有）——本类补上，
 * 让「每个渠道插件可在单一目录内独立装配与验证」（FR-007）对五家渠道全部成立。</p>
 *
 * <p>不断言真实渠道协议（那需要 {@code stripe listen} 与真实密钥）——
 * 只钉三件事：① 工厂装配与自描述；② 能力校验在触达网关<b>之前</b>（模板方法第①步）；
 * ③ 未染色（MOCK 模态）下单走内核统一 mock 语义，<b>绝不触达</b> Stripe 网关桩。</p>
 */
class StripeChannelPluginTest {

    /** 网关桩：任何方法被调都炸——「MOCK 模态绝不触达真实网关」就靠它证明。 */
    private static final class UntouchableGateway implements StripeGateway {
        @Override
        public CheckoutResult createCheckout(String paymentNo, long amountMinor, String currency,
                                             String subject, String successUrl, String cancelUrl,
                                             Instant expiresAt) {
            throw new UnsupportedOperationException("MOCK 模态 MUST NOT 触达 Stripe 网关");
        }

        @Override
        public CheckoutStatus retrieveCheckout(String sessionId) {
            throw new UnsupportedOperationException("MOCK 模态 MUST NOT 触达 Stripe 网关");
        }

        @Override
        public RefundOutcome refund(String paymentIntentId, String refundNo, long amountMinor,
                                    String currency, String reason) {
            throw new UnsupportedOperationException("MOCK 模态 MUST NOT 触达 Stripe 网关");
        }

        @Override
        public StripeEventView verifyAndRead(String rawBody, String signatureHeader) {
            throw new UnsupportedOperationException("MOCK 模态 MUST NOT 触达 Stripe 网关");
        }
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    private static StripeChannelPlugin plugin() {
        return new StripeChannelPlugin(new UntouchableGateway(), new StripeSandboxProperties());
    }

    private static ChargeRequest charge(PaymentScene scene) {
        return new ChargeRequest("PM-STRIPE-TEST-000000001", 10_00L, "USD", "STRIPE",
                scene, Goods.of("Stripe 装配测试商品"),
                Instant.now().plusSeconds(300), null, null, null, null);
    }

    @Test
    @DisplayName("工厂独立装配：descriptor code=STRIPE、支持真实模式、回调路径=STRIPE [FR-007]")
    void factoryAssemblesSelfDescribedPlugin() {
        ChannelPlugin plugin = new StripeChannelPluginFactory(new StripeSandboxProperties()).create();

        ChannelPluginDescriptor descriptor = plugin.descriptor();
        assertThat(descriptor.code()).isEqualTo("STRIPE");
        assertThat(descriptor.supportsRealMode()).isTrue();
        assertThat(descriptor.callbackPath()).isEqualTo("STRIPE");
        // 场景按真实能力收窄（托管收银台只有 WEB / H5；声明支持却拿不到凭证是错误页）
        assertThat(descriptor.supportedScenes()).containsExactlyInAnyOrder(PaymentScene.WEB, PaymentScene.H5);
    }

    @Test
    @DisplayName("能力校验先于网关触达：未实现的 NATIVE 场景被拒，桩网关全程无感 [FR-008]")
    void unsupportedSceneRejectedBeforeGateway() {
        StripeChannelPlugin plugin = plugin();

        assertThatThrownBy(() -> plugin.charge(charge(PaymentScene.NATIVE)))
                .as("Stripe Checkout 未实现 NATIVE，必须在校验阶段拒绝而非触达网关")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("MOCK 模态下单：内核统一 mock 语义，网关桩零触达（尾数 00 ⇒ SUCCESS）")
    void mockModeChargeNeverTouchesGateway() {
        DyeContext.clear(); // 未染色 ⇒ MOCK 模态

        ChannelResult result = plugin().charge(charge(PaymentScene.WEB));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(result.channelReference()).isNotBlank();
    }

    @Test
    @DisplayName("C-1 同口径：enabled=false 插件照常装配，MOCK 模态不受门控影响")
    void disabledPropertiesDoNotBlockMockMode() {
        StripeSandboxProperties properties = new StripeSandboxProperties();
        properties.setEnabled(false); // 默认即 false，显式写出让语义可读

        StripeChannelPlugin plugin = new StripeChannelPlugin(new UntouchableGateway(), properties);
        DyeContext.clear();

        ChannelResult result = plugin.charge(charge(PaymentScene.H5));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
    }
}
