package com.payment.channelgateway.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.trace.TraceContext;
import com.payment.channelgateway.application.ChannelRegistry;
import com.payment.channelgateway.application.ChannelRouter;
import com.payment.channelgateway.application.RouteContext;
import com.payment.channelgateway.infra.config.RoutingProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 规则化确定性路由器（Feature 028 / FR-019~FR-023，ADR-0073）。
 *
 * <p><b>决策顺序（FR-020）</b>：
 * <ol>
 *   <li>{@code routing.enabled=false} → <b>回落旧行为</b>：{@code requestedChannelCode} 必填，
 *       为空则 {@code 400 INVALID_ARGUMENT}（FR-033：灰度开关，一键退回今天的行为）；</li>
 *   <li>{@code requestedChannelCode} 非空 → 校验<b>已注册</b>（<b>不校验 {@code enabled}</b>）
 *       → 原样返回（US3：对显式意图零干预。{@code disabled} 显式渠道记 warn 指标，但不拦截）；</li>
 *   <li>否则取 {@code enabled=true} 且 {@code priority} 最小者；<b>同优先级按渠道码字典序</b>；</li>
 *   <li>候选集为空 → {@code BizException(NO_AVAILABLE_CHANNEL)} → {@code 409}。</li>
 * </ol>
 *
 * <p>叠加 {@code availability}（FR-034）：{@code DOWN} 排除出候选集；{@code DEGRADED} 保留候选
 * 但排序降级（排在所有 UP 之后）；显式指定 {@code DOWN} → {@code 409 CHANNEL_UNAVAILABLE}。</p>
 *
 * <p><b>硬约束</b>：INV-3 确定性（无随机数/时间/计数器，FR-021）；FR-022 调用失败后不改选；
 * FR-023 选路发生在建单之前；FR-034 尾注不读出站弹性组件状态（034-F 起出站无熔断器）。</p>
 */
@Component
public class ConfiguredChannelRouter implements ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredChannelRouter.class);
    private static final String MODULE = "payment";
    /**
     * 路由决策指标（FR-041）。
     *
     * <p>FR-041 原文写作 {@code payment_routing_total}。此处用点分名 {@code payment.routing}
     * 以与全仓既有命名一致（{@code payment.timeout} / {@code payment.retry} …）；
     * Micrometer 的 Prometheus 命名器会把它导出为 {@code payment_routing_total}——
     * <b>对外暴露名与 FR-041 完全一致</b>，仅源码字面量不同。</p>
     */
    static final String ROUTING_METRIC = "payment.routing";

    private final ChannelRegistry registry;
    private final RoutingProperties properties;
    private final BusinessMetrics metrics;

    public ConfiguredChannelRouter(ChannelRegistry registry,
                                   RoutingProperties properties,
                                   BusinessMetrics metrics) {
        this.registry = registry;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String route(RouteContext context) {
        Set<String> registered = registry.registeredCodes();

        // ① 灰度关闭：回落旧行为，渠道码必填
        if (!properties.isEnabled()) {
            if (!context.hasRequestedCode()) {
                throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                        "channelCode is required when payment.routing.enabled=false "
                                + "(legacy behaviour); registered channels: " + registered);
            }
            return explicit(context.requestedChannelCode(), registered);
        }

        // ② 显式指定：零干预（不校验 enabled）
        if (context.hasRequestedCode()) {
            return explicit(context.requestedChannelCode(), registered);
        }

        // ③ 自动选路：enabled=true 且 priority 最小；同优先级按码字典序；DOWN 排除、DEGRADED 降级
        String routed = selectAuto(registered);

        // ④ 无候选
        if (routed == null) {
            metrics.counter(ROUTING_METRIC, 1.0, "module", MODULE, "result", "no_available_channel");
            // FR-043：配置事故需要被看见
            log.warn("no available channel for auto-routing traceId={} registered={} rules={}",
                    TraceContext.getTraceId(), registered, describeRules(registered));
            throw BizException.of(ErrorCodes.NO_AVAILABLE_CHANNEL,
                    "no available channel: all candidates are disabled or unavailable; registered channels: "
                            + registered + "; enable at least one via payment.routing.channels.<CODE>.enabled");
        }

        metrics.counter(ROUTING_METRIC, 1.0, "module", MODULE, "result", "routed", "routed", routed);
        // FR-042：每次路由落一条 INFO，含决策依据与 traceId（MDC 由日志框架携带）
        log.info("routed channelCode={} requestedCode=null basis=auto(priority) traceId={}",
                routed, TraceContext.getTraceId());
        return routed;
    }

    /** 显式指定路径：校验已注册（不校验 enabled），DOWN 则明确拒绝（FR-034）。 */
    private String explicit(String requestedCode, Set<String> registered) {
        String code = requestedCode.trim().toUpperCase();
        if (!registered.contains(code)) {
            // FR-029：列出已注册清单，不静默回落
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "unknown channelCode '" + requestedCode + "'; registered channels: " + registered);
        }

        RoutingProperties.AvailabilityStatus status = properties.statusOf(code);
        if (status == RoutingProperties.AvailabilityStatus.DOWN) {
            metrics.counter(ROUTING_METRIC, 1.0, "module", MODULE, "result", "unavailable_explicit");
            // FR-034：明确拒绝，不偷偷改选——不篡改调用方意图
            log.warn("explicitly requested channel is DOWN channelCode={} traceId={}",
                    code, TraceContext.getTraceId());
            throw BizException.of(ErrorCodes.CHANNEL_UNAVAILABLE,
                    "explicitly requested channel is unavailable (status=DOWN): " + code);
        }

        // L3：显式指定 enabled=false 的渠道不拦截（enabled 语义是「别自动挑我」）
        if (!properties.isChannelEnabled(code)) {
            metrics.counter(ROUTING_METRIC, 1.0, "module", MODULE, "result", "explicit_disabled");
            log.warn("explicitly requested a disabled channel (allowed by design) channelCode={} traceId={}",
                    code, TraceContext.getTraceId());
        } else {
            metrics.counter(ROUTING_METRIC, 1.0, "module", MODULE, "result", "explicit");
        }
        log.info("routed channelCode={} requestedCode={} basis=explicit traceId={}",
                code, requestedCode, TraceContext.getTraceId());
        return code;
    }

    /**
     * 自动选路（INV-3 确定性）：按 (降级标记, priority, channelCode) 三元组排序取第一个。
     *
     * <p>排序键全部来自静态输入——无随机数、无时间、无进程级计数器（FR-021）。
     * {@code channelCode} 作为最终 tie-breaker 保证同优先级下结果唯一确定。</p>
     */
    private String selectAuto(Set<String> registered) {
        List<Candidate> candidates = new ArrayList<>();
        for (String code : registered) {
            if (!properties.isChannelEnabled(code)) {
                continue; // 未启用或未配置规则 → 不参与自动选路
            }
            RoutingProperties.AvailabilityStatus status = properties.statusOf(code);
            if (status == RoutingProperties.AvailabilityStatus.DOWN) {
                continue; // DOWN 排除出候选集（FR-034）
            }
            Integer priority = properties.priorityOf(code);
            if (priority == null) {
                continue; // 无 priority 无法排序（启动期已由 FR-032 拒绝，此处防御）
            }
            // DEGRADED 排序降级：degradedRank 1 排在所有 UP(0) 之后
            int degradedRank = status == RoutingProperties.AvailabilityStatus.DEGRADED ? 1 : 0;
            candidates.add(new Candidate(code, priority, degradedRank));
        }
        return candidates.stream()
                .min(Comparator.comparingInt(Candidate::degradedRank)
                        .thenComparingInt(Candidate::priority)
                        .thenComparing(Candidate::code)) // 字典序 tie-breaker
                .map(Candidate::code)
                .orElse(null);
    }

    /**
     * 候选排序快照（FR-045 dry-run 端点用）：返回按路由实序排列的码，附排除理由。
     * 不产生任何副作用。
     */
    public RoutePreview preview() {
        Set<String> registered = registry.registeredCodes();
        List<Candidate> included = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (String code : registered) {
            RoutingProperties.AvailabilityStatus status = properties.statusOf(code);
            if (!properties.isChannelEnabled(code)) {
                excluded.add(new Excluded(code, "disabled (enabled=false)"));
                continue;
            }
            if (status == RoutingProperties.AvailabilityStatus.DOWN) {
                excluded.add(new Excluded(code, "unavailable (status=DOWN)"));
                continue;
            }
            Integer priority = properties.priorityOf(code);
            int degradedRank = status == RoutingProperties.AvailabilityStatus.DEGRADED ? 1 : 0;
            included.add(new Candidate(code, priority == null ? Integer.MAX_VALUE : priority, degradedRank));
        }
        included.sort(Comparator.comparingInt(Candidate::degradedRank)
                .thenComparingInt(Candidate::priority)
                .thenComparing(Candidate::code));
        List<String> ordered = included.stream().map(Candidate::code).collect(Collectors.toList());
        return new RoutePreview(ordered.isEmpty() ? null : ordered.get(0), ordered, excluded);
    }

    private String describeRules(Set<String> registered) {
        return registered.stream()
                .map(c -> c + "{enabled=" + properties.isChannelEnabled(c)
                        + ", priority=" + properties.priorityOf(c)
                        + ", status=" + properties.statusOf(c) + "}")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** 排序候选。 */
    private record Candidate(String code, int priority, int degradedRank) {
    }

    /** 被排除的候选与理由。 */
    public record Excluded(String code, String reason) {
    }

    /** 路由预览快照（FR-045）。 */
    public record RoutePreview(String wouldRouteTo, List<String> orderedCandidates,
                               List<Excluded> excluded) {
    }
}
