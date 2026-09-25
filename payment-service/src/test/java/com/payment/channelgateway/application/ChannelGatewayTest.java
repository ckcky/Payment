package com.payment.channelgateway.application;

import com.payment.channelgateway.application.spi.ChannelPlugin;
import com.payment.common.core.error.BizException;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentRefundService;
import com.payment.payment.application.reliability.ChannelQueryService;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.application.refund.RefundUnknownQueryScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道网关门面（spec 037 / FR-007 / FR-008 / INV-1 / SC-002）。
 *
 * <h3>两条判据</h3>
 * <ol>
 *   <li><b>行为等价</b>：门面把「先路由、再取实现」的拼装收进网关域，对调用方语义不变
 *       ——按渠道码精确解析、未注册即 {@code INVALID_ARGUMENT}（不静默回落）。</li>
 *   <li><b>结构收口（SC-002）</b>：<b>Payment 侧</b>（{@code com.payment.payment..}）不再持有
 *       {@link ChannelRegistry} / {@link ChannelRouter} / {@link ChannelPlugin}
 *       这三件渠道内部件——字段与构造器参数都不许出现（反射实证，非文本 grep）。</li>
 * </ol>
 *
 * <h3>为什么判据 2 只约束 Payment 侧</h3>
 * <p>038 把渠道网关件整体搬进顶层包 {@code com.payment.channelgateway..}，于是
 * 「谁是 Payment 侧」有了编译期可判定的答案：{@code com.payment.payment..}。
 * 网关域自己的 {@code api} 层（{@code ChannelAdminController} /
 * {@code ChannelPluginCallbackController}）持有 {@link ChannelRegistry} 是<b>域内合法</b>——
 * 它们是网关域对外的 HTTP 入口，不是越过边界的调用方。故本测试反向断言这些类
 * <b>必须</b>落在 {@code com.payment.channelgateway..}，防止「把违规类挪个包就变绿」。</p>
 */
class ChannelGatewayTest {

    private static final String CODE = "MOCK";

    /** Payment 侧：资金动作域。这些类 MUST 只经门面接触渠道网关。 */
    private static final List<Class<?>> PAYMENT_SIDE = List.of(
            PaymentApplicationService.class,
            PaymentRefundService.class,
            ChannelQueryService.class,
            PaymentRetryService.class,
            RefundUnknownQueryScheduler.class);

    /** 网关域的 HTTP 入口：合法持有注册表，但必须归属网关域（防「挪包变绿」）。 */
    private static final List<Class<?>> GATEWAY_API_ENTRY_POINTS = List.of(
            com.payment.channelgateway.api.ChannelAdminController.class,
            com.payment.channelgateway.api.ChannelPluginCallbackController.class);

    // ---------- 行为等价 ----------

    @Test
    @DisplayName("pay：按 channelCode 解析后扣款，返回渠道结果")
    void payDelegatesToResolvedChannel() {
        StubChannel channel = new StubChannel(CODE);
        ChannelGateway gateway = new DefaultChannelGateway(new SingleChannelRegistry(channel));

        ChargeRequest request = new ChargeRequest("PM1", 100L, "CNY", CODE);
        ChannelResult result = gateway.pay(CODE, request);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(channel.chargeCalls).isEqualTo(1);
        assertThat(channel.lastRequest).isSameAs(request);
    }

    @Test
    @DisplayName("refund：按 channelCode 解析后退款（INV-6 不重新选路）")
    void refundDelegatesToResolvedChannel() {
        StubChannel channel = new StubChannel(CODE);
        ChannelGateway gateway = new DefaultChannelGateway(new SingleChannelRegistry(channel));

        ChannelResult result =
                gateway.refund(CODE, new RefundRequest("PM1", "PMRF1", 100L, "CNY", CODE));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(channel.refundCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("query：显式渠道码 + 查询请求 ⇒ 走 queryStatus")
    void queryDelegatesToResolvedChannel() {
        StubChannel channel = new StubChannel(CODE);
        ChannelGateway gateway = new DefaultChannelGateway(new SingleChannelRegistry(channel));

        ChannelResult result = gateway.query(CODE,
                new QueryStatusRequest("PM1", "TX1", "idem-1", "ch-txn-1"));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(channel.queryCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("未注册渠道码 ⇒ INVALID_ARGUMENT（不静默回落 MOCK）")
    void unknownChannelCodeIsRejected() {
        ChannelGateway gateway =
                new DefaultChannelGateway(new SingleChannelRegistry(new StubChannel(CODE)));

        assertThatThrownBy(() -> gateway.pay("UNKNOWN", new ChargeRequest("PM1", 100L, "CNY", "UNKNOWN")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    @DisplayName("supportedScenes / registeredChannelCodes：能力查询透传")
    void capabilityQueriesAreDelegated() {
        ChannelGateway gateway =
                new DefaultChannelGateway(new SingleChannelRegistry(new StubChannel(CODE)));

        assertThat(gateway.supportedScenes(CODE)).isEqualTo(Set.of(PaymentScene.WEB));
        assertThat(gateway.registeredChannelCodes()).containsExactly(CODE);
    }

    @Test
    @DisplayName("route：有 Router 走 Router；无 Router（兼容路径）用显式码，缺省 MOCK")
    void routeUsesRouterWhenPresent() {
        ChannelGateway withRouter = new DefaultChannelGateway(
                new SingleChannelRegistry(new StubChannel(CODE)), ctx -> "ROUTED");
        assertThat(withRouter.route(RouteContext.auto(100L, "CNY"))).isEqualTo("ROUTED");

        ChannelGateway withoutRouter =
                new DefaultChannelGateway(new SingleChannelRegistry(new StubChannel(CODE)), null);
        assertThat(withoutRouter.route(new RouteContext(100L, "CNY", "ALIPAY"))).isEqualTo("ALIPAY");
        assertThat(withoutRouter.route(RouteContext.auto(100L, "CNY"))).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("requireRegistered：已注册通过；未注册抛 INVALID_ARGUMENT（与 query 同口径）")
    void requireRegisteredValidatesRegistration() {
        ChannelGateway gateway =
                new DefaultChannelGateway(new SingleChannelRegistry(new StubChannel(CODE)));

        gateway.requireRegistered(CODE); // 已注册：不抛

        assertThatThrownBy(() -> gateway.requireRegistered("UNKNOWN"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    @DisplayName("兼容路径：无注册表 ⇒ supportedScenes 返回 null（调用方据此跳过场景校验，NFR-2）")
    void compatPathWithoutRegistryYieldsNullCapabilities() {
        ChannelGateway gateway = new DefaultChannelGateway(null, null);

        assertThat(gateway.supportedScenes(CODE)).isNull();
        assertThat(gateway.route(new RouteContext(100L, "CNY", "ALIPAY"))).isEqualTo("ALIPAY");
    }

    // ---------- 结构收口（SC-002） ----------

    @Test
    @DisplayName("门面归属网关域：ChannelGateway 落在 com.payment.channelgateway..（FR-007）")
    void gatewayFacadeResidesInGatewayDomain() {
        assertThat(ChannelGateway.class.getPackageName())
                .as("门面是渠道网关域的对外入口，必须归属网关域")
                .startsWith("com.payment.channelgateway");
    }

    @Test
    @DisplayName("SC-002：Payment 侧不再持有 ChannelRegistry / ChannelRouter / ChannelPlugin")
    void paymentSideHoldsNoChannelInternals() {
        Set<Class<?>> forbidden = Set.of(ChannelRegistry.class, ChannelRouter.class, ChannelPlugin.class);

        for (Class<?> type : PAYMENT_SIDE) {
            List<Class<?>> held = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                held.add(field.getType());
            }
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                held.addAll(Arrays.asList(ctor.getParameterTypes()));
            }
            assertThat(held)
                    .as("%s 的字段与构造器参数不得出现渠道内部件（INV-1 / SC-002）", type.getSimpleName())
                    .doesNotContainAnyElementsOf(forbidden);
        }
    }

    @Test
    @DisplayName("防「挪包变绿」：网关域 HTTP 入口必须归属 com.payment.channelgateway..（038 包边界）")
    void gatewayEntryPointsStayInsideGatewayDomain() {
        for (Class<?> type : GATEWAY_API_ENTRY_POINTS) {
            assertThat(type.getPackageName())
                    .as("%s 是网关域的 HTTP 入口，越域即包边界回退", type.getSimpleName())
                    .startsWith("com.payment.channelgateway");
        }
    }

    /** 测试用渠道桩：记录调用次数，恒成功。 */
    private static final class StubChannel implements PaymentChannel {

        private final String code;
        private int chargeCalls;
        private int refundCalls;
        private int queryCalls;
        private ChargeRequest lastRequest;

        private StubChannel(String code) {
            this.code = code;
        }

        @Override
        public String channelCode() {
            return code;
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            chargeCalls++;
            lastRequest = request;
            return ChannelResult.success("ch-ref-1");
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            refundCalls++;
            return ChannelResult.success("ch-refund-1");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            queryCalls++;
            return ChannelResult.success("ch-ref-1");
        }

        @Override
        public Set<PaymentScene> supportedScenes() {
            return Set.of(PaymentScene.WEB);
        }
    }
}
