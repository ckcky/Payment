package com.payment.channelgateway.api;

import com.payment.channelgateway.application.ChannelRegistry;
import com.payment.channelgateway.infra.ConfiguredChannelRouter;
import com.payment.channelgateway.infra.config.RoutingProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道路由只读管理端点（Feature 028 / FR-044、FR-045）。
 *
 * <p>两个端点都<b>只读</b>，不改任何状态（FR-045 明确要求 dry-run 不落库）：
 * <ul>
 *   <li>{@code GET /internal/channels} —— 各渠道 {@code code / status / priority / enabled}，
 *       演示页与排障两用（FR-044）；</li>
 *   <li>{@code GET /internal/channels/route-preview} —— 「此刻不指定渠道会选谁 + 候选排序 +
 *       排除理由」（FR-045）。</li>
 * </ul>
 *
 * <p><b>演示期可用性覆盖</b>：{@code POST /internal/channels/{code}/status} 仅在
 * {@code demo} profile 下注册（{@link Profile}）——生产环境不应存在「随手改路由输入」的入口。
 * 覆盖只改内存中的 {@link RoutingProperties}，重启即失效，不落库。</p>
 */
@RestController
public class ChannelAdminController {

    private final ChannelRegistry registry;
    private final RoutingProperties properties;
    private final ConfiguredChannelRouter router;

    public ChannelAdminController(ChannelRegistry registry,
                                  RoutingProperties properties,
                                  ConfiguredChannelRouter router) {
        this.registry = registry;
        this.properties = properties;
        this.router = router;
    }

    /** FR-044：各渠道身份、可用性、优先级与是否参与自动选路。 */
    @GetMapping("/internal/channels")
    public ChannelListView list() {
        List<ChannelView> views = new ArrayList<>();
        for (String code : registry.registeredCodes()) {
            views.add(new ChannelView(code,
                    properties.statusOf(code).name(),
                    properties.priorityOf(code),
                    properties.isChannelEnabled(code)));
        }
        return new ChannelListView(properties.isEnabled(), views);
    }

    /** FR-045：dry-run 路由预览，无任何落库。 */
    @GetMapping("/internal/channels/route-preview")
    public RoutePreviewView routePreview() {
        ConfiguredChannelRouter.RoutePreview preview = router.preview();
        List<ExcludedView> excluded = preview.excluded().stream()
                .map(e -> new ExcludedView(e.code(), e.reason()))
                .toList();
        return new RoutePreviewView(properties.isEnabled(), preview.wouldRouteTo(),
                preview.orderedCandidates(), excluded);
    }

    /**
     * 演示期可用性覆盖（FR-034 尾注 / Batch G）：仅 {@code demo} profile。
     *
     * <p>不落库、不广播——覆盖仅存活于当前 JVM，用于演示页手动制造 DEGRADED / DOWN 场景。</p>
     */
    @RestController
    @Profile("demo")
    public static class DemoStatusController {

        private final RoutingProperties properties;
        private final ChannelRegistry registry;

        public DemoStatusController(RoutingProperties properties, ChannelRegistry registry) {
            this.properties = properties;
            this.registry = registry;
        }

        @PostMapping("/internal/channels/{code}/status")
        public Map<String, Object> override(@PathVariable String code,
                                            @RequestBody Map<String, String> body) {
            String raw = body == null ? null : body.get("status");
            String normalized = raw == null ? "" : raw.trim().toUpperCase();
            RoutingProperties.AvailabilityStatus status;
            try {
                status = RoutingProperties.AvailabilityStatus.valueOf(normalized);
            } catch (IllegalArgumentException ex) {
                throw com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.INVALID_ARGUMENT,
                        "unknown availability status '" + raw + "'; legal values: "
                                + java.util.Arrays.toString(RoutingProperties.AvailabilityStatus.values()));
            }
            if (!registry.registeredCodes().contains(code.trim().toUpperCase())) {
                throw com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.INVALID_ARGUMENT,
                        "unknown channelCode '" + code + "'; registered channels: " + registry.registeredCodes());
            }
            Map<String, RoutingProperties.Availability> availability = properties.getAvailability();
            if (availability == null || availability.isEmpty()) {
                availability = new LinkedHashMap<>();
                properties.setAvailability(availability);
            }
            RoutingProperties.Availability entry = new RoutingProperties.Availability();
            entry.setStatus(status);
            availability.put(code.trim().toUpperCase(), entry);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("channelCode", code.trim().toUpperCase());
            response.put("status", status.name());
            response.put("note", "in-memory override for the demo profile only; reset on restart");
            return response;
        }
    }

    /** FR-044 响应。 */
    public record ChannelListView(boolean routingEnabled, List<ChannelView> channels) {
    }

    /** 单个渠道视图。 */
    public record ChannelView(String code, String status, Integer priority, boolean enabled) {
    }

    /** FR-045 响应。 */
    public record RoutePreviewView(boolean routingEnabled, String wouldRouteTo,
                                   List<String> orderedCandidates, List<ExcludedView> excluded) {
    }

    /** 被排除的候选与理由。 */
    public record ExcludedView(String code, String reason) {
    }
}
