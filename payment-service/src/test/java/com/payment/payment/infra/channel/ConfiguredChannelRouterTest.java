package com.payment.payment.infra.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.RouteContext;
import com.payment.payment.infra.config.RoutingProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 渠道路由族测试（Feature 028 / FR-015~FR-023、FR-029、FR-032~FR-035）。
 *
 * <p>覆盖四组：注册表寻址与错误口径、确定性选路（INV-3）、显式指定零干预（US3）、
 * 可用性叠加（FR-034）与启动强校验（FR-032）。</p>
 */
class ConfiguredChannelRouterTest {

    /** 测试用渠道：只声明身份，行为不被本族测试使用。 */
    private static PaymentChannel stub(String code) {
        return new PaymentChannel() {
            @Override
            public String channelCode() {
                return code;
            }

            @Override
            public com.payment.payment.application.channel.ChannelResult charge(
                    com.payment.payment.application.channel.ChargeRequest request) {
                throw new UnsupportedOperationException("not used by routing tests");
            }

            @Override
            public com.payment.payment.application.channel.ChannelResult refund(
                    com.payment.payment.application.channel.RefundRequest request) {
                throw new UnsupportedOperationException("not used by routing tests");
            }

            @Override
            public com.payment.payment.application.channel.ChannelResult queryStatus(
                    com.payment.payment.application.channel.QueryStatusRequest request) {
                throw new UnsupportedOperationException("not used by routing tests");
            }
        };
    }

    private static RoutingProperties props(boolean enabled, Map<String, int[]> rules) {
        RoutingProperties properties = new RoutingProperties();
        properties.setEnabled(enabled);
        Map<String, RoutingProperties.ChannelRule> channels = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : rules.entrySet()) {
            RoutingProperties.ChannelRule rule = new RoutingProperties.ChannelRule();
            rule.setEnabled(e.getValue()[0] == 1);
            rule.setPriority(e.getValue()[1]);
            channels.put(e.getKey(), rule);
        }
        properties.setChannels(channels);
        return properties;
    }

    /** 三个渠道 + 默认优先级规则（ALIPAY 10 < WECHAT 20 < MOCK 90）。 */
    private static RoutingProperties defaultProps() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {1, 10});
        rules.put("WECHAT", new int[] {1, 20});
        rules.put("MOCK", new int[] {1, 90});
        return props(true, rules);
    }

    private static ChannelRegistry registry(List<PaymentChannel> channels, RoutingProperties properties) {
        return new SpringChannelRegistry(channels, properties);
    }

    private static ConfiguredChannelRouter router(List<PaymentChannel> channels, RoutingProperties properties) {
        return new ConfiguredChannelRouter(registry(channels, properties), properties, new NoopBusinessMetrics());
    }

    private static final List<PaymentChannel> ALL = List.of(stub("ALIPAY"), stub("WECHAT"), stub("MOCK"));

    // ---- 注册表（FR-015 / FR-018 / FR-029） ----

    @Test
    void registryResolvesByCodeCaseInsensitively() {
        ChannelRegistry registry = registry(ALL, defaultProps());

        assertThat(registry.resolve("alipay").channelCode()).isEqualTo("ALIPAY");
        assertThat(registry.resolve("  WeChat  ").channelCode()).isEqualTo("WECHAT");
        assertThat(registry.registeredCodes()).containsExactly("ALIPAY", "MOCK", "WECHAT");
    }

    @Test
    void registryUnknownCodeListsRegisteredChannelsAndDoesNotFallBack() {
        ChannelRegistry registry = registry(ALL, defaultProps());

        assertThatThrownBy(() -> registry.resolve("PAYPAL"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT))
                // FR-029：错误信息必须列出已注册清单——不静默回落 MOCK
                .hasMessageContaining("ALIPAY")
                .hasMessageContaining("MOCK")
                .hasMessageContaining("WECHAT");
    }

    @Test
    void registryRejectsBlankCode() {
        ChannelRegistry registry = registry(ALL, defaultProps());

        assertThatThrownBy(() -> registry.resolve("   "))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    /** FR-018 / ADR-0049：重复 code 是结构性错误，MUST 在启动期炸，不能静默覆盖。 */
    @Test
    void registryRejectsDuplicateChannelCode() {
        assertThatThrownBy(() -> registry(List.of(stub("MOCK"), stub("mock")), defaultProps()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate channelCode");
    }

    @Test
    void registryRejectsBlankChannelCode() {
        assertThatThrownBy(() -> registry(List.of(stub("  ")), defaultProps()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank channelCode");
    }

    // ---- 自动选路（FR-019 / FR-021 / INV-3） ----

    @Test
    void autoRoutingPicksLowestPriorityEnabledChannel() {
        ConfiguredChannelRouter router = router(ALL, defaultProps());

        assertThat(router.route(RouteContext.auto(100, "CNY"))).isEqualTo("ALIPAY");
    }

    @Test
    void autoRoutingSkipsDisabledChannel() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {0, 10}); // 最高优先级但禁用
        rules.put("WECHAT", new int[] {1, 20});
        rules.put("MOCK", new int[] {1, 90});
        ConfiguredChannelRouter router = router(ALL, props(true, rules));

        assertThat(router.route(RouteContext.auto(100, "CNY"))).isEqualTo("WECHAT");
    }

    /** INV-3：同优先级时按渠道码字典序，结果必须唯一确定（无随机、无计数器）。 */
    @Test
    void autoRoutingTieBreaksByChannelCodeLexicographically() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("WECHAT", new int[] {1, 10});
        rules.put("ALIPAY", new int[] {1, 10}); // 同优先级
        rules.put("MOCK", new int[] {1, 10});
        ConfiguredChannelRouter router = router(ALL, props(true, rules));

        // ALIPAY < MOCK < WECHAT 字典序
        assertThat(router.route(RouteContext.auto(100, "CNY"))).isEqualTo("ALIPAY");
        // INV-3 确定性：重复调用结果恒等
        for (int i = 0; i < 20; i++) {
            assertThat(router.route(RouteContext.auto(100, "CNY"))).isEqualTo("ALIPAY");
        }
    }

    /** 遍历注册顺序不同的 Map，证明结果只由 (priority, code) 决定，与装配顺序无关（INV-3）。 */
    @Test
    void autoRoutingIsIndependentOfRegistrationOrder() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("WECHAT", new int[] {1, 20});
        rules.put("ALIPAY", new int[] {1, 10});
        rules.put("MOCK", new int[] {1, 90});
        RoutingProperties properties = props(true, rules);

        ConfiguredChannelRouter forward = new ConfiguredChannelRouter(
                registry(List.of(stub("ALIPAY"), stub("WECHAT"), stub("MOCK")), properties),
                properties, new NoopBusinessMetrics());
        ConfiguredChannelRouter reverse = new ConfiguredChannelRouter(
                registry(List.of(stub("MOCK"), stub("WECHAT"), stub("ALIPAY")), properties),
                properties, new NoopBusinessMetrics());

        assertThat(forward.route(RouteContext.auto(1, "CNY")))
                .isEqualTo(reverse.route(RouteContext.auto(1, "CNY")))
                .isEqualTo("ALIPAY");
    }

    @Test
    void autoRoutingWithoutCandidatesRaisesNoAvailableChannel() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {0, 10});
        rules.put("WECHAT", new int[] {0, 20});
        rules.put("MOCK", new int[] {0, 90});
        // 全禁用会被 FR-032 启动校验拦下，故此处绕过校验直接构造路由器验证运行期兜底
        RoutingProperties properties = props(true, rules);
        ConfiguredChannelRouter router = new ConfiguredChannelRouter(
                new SingleChannelRegistry(stub("MOCK")), properties, new NoopBusinessMetrics());

        assertThatThrownBy(() -> router.route(RouteContext.auto(100, "CNY")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCodes.NO_AVAILABLE_CHANNEL));
    }

    // ---- 显式指定（US3 / FR-020② / FR-034） ----

    @Test
    void explicitChannelIsHonouredEvenWhenDisabled() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {1, 10});
        rules.put("WECHAT", new int[] {0, 20}); // 禁用
        ConfiguredChannelRouter router = router(ALL, props(true, rules));

        // enabled=false 的语义是「别自动挑我」，不是「禁止使用」（L3）
        assertThat(router.route(new RouteContext(100, "CNY", "WECHAT"))).isEqualTo("WECHAT");
    }

    @Test
    void explicitChannelOverridesPriority() {
        ConfiguredChannelRouter router = router(ALL, defaultProps());

        assertThat(router.route(new RouteContext(100, "CNY", "MOCK"))).isEqualTo("MOCK");
    }

    @Test
    void explicitUnregisteredChannelRaisesInvalidArgument() {
        ConfiguredChannelRouter router = router(ALL, defaultProps());

        assertThatThrownBy(() -> router.route(new RouteContext(100, "CNY", "PAYPAL")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT))
                .hasMessageContaining("ALIPAY");
    }

    @Test
    void explicitDownChannelRaisesUnavailable() {
        RoutingProperties properties = defaultProps();
        Map<String, RoutingProperties.Availability> availability = new LinkedHashMap<>();
        RoutingProperties.Availability down = new RoutingProperties.Availability();
        down.setStatus(RoutingProperties.AvailabilityStatus.DOWN);
        availability.put("WECHAT", down);
        properties.setAvailability(availability);
        ConfiguredChannelRouter router = router(ALL, properties);

        // FR-034：明确拒绝，不偷偷改选——不篡改调用方意图
        assertThatThrownBy(() -> router.route(new RouteContext(100, "CNY", "WECHAT")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCodes.CHANNEL_UNAVAILABLE));
    }

    // ---- 灰度开关（FR-020① / FR-033） ----

    @Test
    void routingDisabledFallsBackToLegacyBehaviourRequiringExplicitCode() {
        ConfiguredChannelRouter router = router(ALL, props(false, new LinkedHashMap<>(Map.of(
                "MOCK", new int[] {1, 90}))));

        // 灰度关闭 = 旧行为：channelCode 必填
        assertThatThrownBy(() -> router.route(RouteContext.auto(100, "CNY")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(router.route(new RouteContext(100, "CNY", "MOCK"))).isEqualTo("MOCK");
    }

    // ---- 可用性叠加（FR-034 / FR-035） ----

    @Test
    void degradedChannelIsDeprioritisedBelowUpChannels() {
        RouterFixture fixture = defaultPropsWithAvailability("ALIPAY",
                RoutingProperties.AvailabilityStatus.DEGRADED);

        // ALIPAY 优先级最优但降级 → 让位给 UP 的 WECHAT
        assertThat(fixture.router.route(RouteContext.auto(100, "CNY"))).isEqualTo("WECHAT");
    }

    @Test
    void downChannelIsExcludedFromAutoRouting() {
        RouterFixture fixture = defaultPropsWithAvailability("ALIPAY",
                RoutingProperties.AvailabilityStatus.DOWN);

        assertThat(fixture.router.route(RouteContext.auto(100, "CNY"))).isEqualTo("WECHAT");
    }

    @Test
    void routePreviewReportsOrderingAndExclusionReasons() {
        RouterFixture fixture = defaultPropsWithAvailability("ALIPAY",
                RoutingProperties.AvailabilityStatus.DOWN);

        ConfiguredChannelRouter.RoutePreview preview = fixture.router.preview();

        assertThat(preview.wouldRouteTo()).isEqualTo("WECHAT");
        assertThat(preview.orderedCandidates()).containsExactly("WECHAT", "MOCK");
        assertThat(preview.excluded()).extracting(ConfiguredChannelRouter.Excluded::code)
                .containsExactly("ALIPAY");
        assertThat(preview.excluded().get(0).reason()).contains("DOWN");
    }

    // ---- 启动强校验（FR-032 / ADR-0049） ----

    @Test
    void startupValidationRejectsEmptyChannelRules() {
        assertThatThrownBy(() -> registry(ALL, props(true, new LinkedHashMap<>())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.routing.channels is empty");
    }

    @Test
    void startupValidationRejectsMissingPriority() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {1, 10});
        rules.put("MOCK", new int[] {1, 90});
        RoutingProperties properties = props(true, rules);

        // priority 为空 → 启动期拒绝（否则自动选路无法排序）
        properties.getChannels().get("MOCK").setPriority(null);

        assertThatThrownBy(() -> registry(ALL, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("priority is missing");
    }

    @Test
    void startupValidationRejectsUnregisteredChannelCode() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {1, 10});
        rules.put("PAYPAL", new int[] {1, 20}); // 未注册
        RoutingProperties properties = props(true, rules);

        assertThatThrownBy(() -> registry(ALL, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a registered channel");
    }

    @Test
    void startupValidationRejectsAllChannelsDisabled() {
        Map<String, int[]> rules = new LinkedHashMap<>();
        rules.put("ALIPAY", new int[] {0, 10});
        rules.put("MOCK", new int[] {0, 90});

        assertThatThrownBy(() -> registry(ALL, props(true, rules)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all channels are enabled=false");
    }

    private record RouterFixture(ConfiguredChannelRouter router) {
    }

    private static RouterFixture defaultPropsWithAvailability(String code,
                                                              RoutingProperties.AvailabilityStatus status) {
        RoutingProperties properties = defaultProps();
        Map<String, RoutingProperties.Availability> availability = new LinkedHashMap<>();
        RoutingProperties.Availability entry = new RoutingProperties.Availability();
        entry.setStatus(status);
        availability.put(code, entry);
        properties.setAvailability(availability);
        return new RouterFixture(router(ALL, properties));
    }
}
