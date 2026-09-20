package com.payment.payment.infra.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.RouteContext;
import com.payment.payment.infra.config.RoutingProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 染色<b>不得</b>影响选路测试（spec 030 / T123 / FR-120，INV-3，SC-A-08）。
 *
 * <p>这是 spec 030 最短小、也最不能少的一条不变量：{@code X-Dye-Tag} 只决定
 * <b>协议怎么实现</b>（mock 模拟 / 沙箱真调），<b>不参与</b>「这笔走哪家渠道」的决策。</p>
 *
 * <p>为什么必须钉住：如果染色渗进选路，就会出现「为了测沙箱而染色，结果订单被路由到了
 * 支付宝——哪怕运营只是想走 MOCK」这种与业务意图无关的副作用。渠道归属是业务事实
 * （落在 {@code payment_attempts.channel_code} 列），不能由排障开关改写。</p>
 *
 * <p>断言方式：对<b>同一组</b> {@link RouteContext}，分别在未染色 / 显式 MOCK / SANDBOX
 * 下选路，要求三次结果<b>逐一相同</b>——既覆盖「只表达支付意图」的自动选路（US2），
 * 也覆盖「显式指定渠道」的零干预路径（US3）。</p>
 */
class DyeNotAffectingRoutingTest {

    private static PaymentChannel stub(String code) {
        return new PaymentChannel() {
            @Override
            public String channelCode() {
                return code;
            }

            @Override
            public com.payment.payment.application.channel.ChannelResult charge(
                    com.payment.payment.application.channel.ChargeRequest request) {
                throw new UnsupportedOperationException("routing tests never call the channel");
            }

            @Override
            public com.payment.payment.application.channel.ChannelResult refund(
                    com.payment.payment.application.channel.RefundRequest request) {
                throw new UnsupportedOperationException("routing tests never call the channel");
            }

            @Override
            public com.payment.payment.application.channel.ChannelResult queryStatus(
                    com.payment.payment.application.channel.QueryStatusRequest request) {
                throw new UnsupportedOperationException("routing tests never call the channel");
            }
        };
    }

    private static RoutingProperties defaultProps() {
        RoutingProperties properties = new RoutingProperties();
        properties.setEnabled(true);
        Map<String, RoutingProperties.ChannelRule> channels = new LinkedHashMap<>();
        channels.put("ALIPAY", rule(true, 10));
        channels.put("WECHAT", rule(true, 20));
        channels.put("MOCK", rule(true, 90));
        properties.setChannels(channels);
        return properties;
    }

    private static RoutingProperties.ChannelRule rule(boolean enabled, int priority) {
        RoutingProperties.ChannelRule r = new RoutingProperties.ChannelRule();
        r.setEnabled(enabled);
        r.setPriority(priority);
        return r;
    }

    private static ConfiguredChannelRouter router() {
        RoutingProperties props = defaultProps();
        ChannelRegistry registry = new SpringChannelRegistry(
                List.of(stub("ALIPAY"), stub("WECHAT"), stub("MOCK")), props);
        return new ConfiguredChannelRouter(registry, props, new NoopBusinessMetrics());
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    private static String routeUnderRouter(ConfiguredChannelRouter router, RouteContext ctx) {
        // route(...) 只产出渠道码字符串，本身不接触渠道协议（INV-4）；
        // 自动选路与显式指定走同一出口，本测试只关心「结果与染色无关」。
        return router.route(ctx);
    }

    @Test
    @DisplayName("自动选路：三种染色下选出**同一**渠道（默认优先级 ALIPAY）[FR-120][INV-3]")
    void autoRoutingIsDyeInvariant() {
        ConfiguredChannelRouter router = router();
        RouteContext ctx = RouteContext.auto(10_00L, "CNY");

        DyeContext.clear();
        String noDye = routeUnderRouter(router, ctx);

        DyeContext.set(DyeMode.MOCK);
        String mockDye = routeUnderRouter(router, ctx);

        DyeContext.set(DyeMode.SANDBOX);
        String sandboxDye = routeUnderRouter(router, ctx);

        assertThat(noDye).isEqualTo("ALIPAY");
        assertThat(mockDye).isEqualTo(noDye);
        assertThat(sandboxDye).isEqualTo(noDye);
    }

    @Test
    @DisplayName("显式指定 MOCK：染色 SANDBOX 也不得改写调用方的显式意图（US3 零干预）[FR-120]")
    void explicitRequestedChannelSurvivesSandboxDye() {
        ConfiguredChannelRouter router = router();
        RouteContext explicitMock = new RouteContext(10_00L, "CNY", "MOCK");

        DyeContext.clear();
        String noDye = routeUnderRouter(router, explicitMock);

        DyeContext.set(DyeMode.SANDBOX);
        String sandboxDye = routeUnderRouter(router, explicitMock);

        // 关键：染了沙箱色，选路结果仍是调用方明确要求的 MOCK
        // ——「协议怎么实现」与「业务走哪家」是两件事
        assertThat(noDye).isEqualTo("MOCK");
        assertThat(sandboxDye).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("显式指定 ALIPAY：未染色时也返回 ALIPAY（染色不是选它的原因）")
    void explicitRequestedChannelIsHonoredWithoutDye() {
        ConfiguredChannelRouter router = router();
        RouteContext explicitAlipay = new RouteContext(10_00L, "CNY", "ALIPAY");

        DyeContext.clear();
        // 去掉染色后**依然**选 ALIPAY ⇒ 证明之前的 ALIPAY 来自显式意图，而非染色
        assertThat(routeUnderRouter(router, explicitAlipay)).isEqualTo("ALIPAY");
    }
}
