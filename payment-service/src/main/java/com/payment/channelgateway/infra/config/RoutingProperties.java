package com.payment.channelgateway.infra.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 渠道路由配置（Feature 028 / FR-031，ADR-0073）：绑定 {@code payment.routing.*}。
 *
 * <pre>{@code
 * payment:
 *   routing:
 *     enabled: true
 *     channels:
 *       ALIPAY: { enabled: true,  priority: 10 }
 *       WECHAT: { enabled: true,  priority: 20 }
 *       DOUYIN: { enabled: false, priority: 30 }
 *       MOCK:   { enabled: true,  priority: 90 }
 *     availability:
 *       ALIPAY: { status: UP }
 * }</pre>
 *
 * <p><b>启动强校验（FR-032）</b>：配置非法 MUST 启动失败并给出合法取值清单——
 * 「配错了却静默走默认」是最难排查的假绿（ADR-0049 第 2 条）。覆盖：</p>
 * <ul>
 *   <li>{@code priority} 缺失或非数字（由绑定期类型转换抛错）；</li>
 *   <li>出现未注册的渠道码（渠道码清单由 {@code ChannelRegistry} 注入）；</li>
 *   <li>全部渠道 {@code enabled=false}（此时路由必然无解，属配置事故）；</li>
 *   <li>{@code availability.status} 非法值。</li>
 * </ul>
 *
 * <p><b>可用性与出站弹性无关（FR-034 尾注 / D6 / S11）</b>：重试/熔断是<b>调用后</b>出站保护，
 * 可用性是<b>调用前</b>路由输入，两者不互喂。本类不读取任何出站弹性组件的状态
 * （034-F 起 payment 出站不接熔断器，spec 034 §1.4 / H-034-1）。</p>
 */
@ConfigurationProperties(prefix = "payment.routing")
public class RoutingProperties {

    /** 可用性状态（FR-034）：静态配置桩，不做真实探测（FR-035 / L1）。 */
    public enum AvailabilityStatus {
        /** 正常：参与候选。 */
        UP,
        /** 降级：保留候选但排序降级（排在所有 UP 之后）。 */
        DEGRADED,
        /** 不可用：排除出候选集；显式指定则 409 CHANNEL_UNAVAILABLE。 */
        DOWN
    }

    /** 单渠道路由配置。 */
    public static class ChannelRule {
        /** 是否参与自动选路（false = 「别自动挑我」，不是「禁止使用」——显式指定仍可用，L3）。 */
        private boolean enabled = true;
        /** 优先级：数值越小越优先；同优先级按渠道码字典序（INV-3 确定性）。 */
        private Integer priority;
        /** 静态可用性（默认 UP）。 */
        private AvailabilityStatus status = AvailabilityStatus.UP;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getPriority() {
            return priority;
        }

        public void setPriority(Integer priority) {
            this.priority = priority;
        }

        public AvailabilityStatus getStatus() {
            return status;
        }

        public void setStatus(AvailabilityStatus status) {
            this.status = status;
        }
    }

    /** 灰度开关：false = 回落旧行为（channelCode 必填，不选路）。 */
    private boolean enabled = true;

    /** 渠道 → 规则。 */
    private Map<String, ChannelRule> channels = new LinkedHashMap<>();

    /** 渠道 → 可用性（FR-034）。缺省视为 UP。 */
    private Map<String, Availability> availability = new LinkedHashMap<>();

    /** 可用性条目（仅 status，便于 yaml 写 `{ status: UP }`）。 */
    public static class Availability {
        private AvailabilityStatus status = AvailabilityStatus.UP;

        public AvailabilityStatus getStatus() {
            return status;
        }

        public void setStatus(AvailabilityStatus status) {
            this.status = status;
        }
    }

    /** 由 {@code ChannelRegistry} 在启动期回填，用于校验「配置里出现未注册渠道码」（FR-032）。 */
    private java.util.Set<String> registeredCodes;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, ChannelRule> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ChannelRule> channels) {
        this.channels = channels == null ? new LinkedHashMap<>() : channels;
    }

    public Map<String, Availability> getAvailability() {
        return availability;
    }

    public void setAvailability(Map<String, Availability> availability) {
        this.availability = availability == null ? new LinkedHashMap<>() : availability;
    }

    /** 供注册表回填已装配渠道码（启动期校验用）。 */
    public void setRegisteredCodes(java.util.Set<String> registeredCodes) {
        this.registeredCodes = registeredCodes;
    }

    // ---- 查询辅助（供 Router / 只读端点使用） ----

    /** 渠道是否参与自动选路；未配置的渠道视为不参与（未配置 = 未纳入路由）。 */
    public boolean isChannelEnabled(String code) {
        ChannelRule rule = channels.get(code);
        return rule != null && rule.isEnabled();
    }

    /** 渠道优先级；未配置返回 {@code null}。 */
    public Integer priorityOf(String code) {
        ChannelRule rule = channels.get(code);
        return rule == null ? null : rule.getPriority();
    }

    /**
     * 渠道可用性（FR-034）：查 {@code availability} 表，缺省 {@code UP}。
     *
     * <p>兼容两种写法：{@code availability.<CODE>.status} 与 {@code channels.<CODE>.status}
     * ——后者为先配后拆历史写法，取非 UP 优先。</p>
     */
    public AvailabilityStatus statusOf(String code) {
        Availability a = availability.get(code);
        AvailabilityStatus fromAvailability = a == null ? null : a.getStatus();
        ChannelRule rule = channels.get(code);
        AvailabilityStatus fromChannels = rule == null ? null : rule.getStatus();
        if (fromAvailability != null && fromAvailability != AvailabilityStatus.UP) {
            return fromAvailability;
        }
        if (fromChannels != null && fromChannels != AvailabilityStatus.UP) {
            return fromChannels;
        }
        return fromAvailability != null ? fromAvailability
                : (fromChannels != null ? fromChannels : AvailabilityStatus.UP);
    }

    /** 配置中声明为可用性入口的渠道码集合（供只读端点列出）。 */
    public Map<String, AvailabilityStatus> allStatuses() {
        Map<String, AvailabilityStatus> result = new LinkedHashMap<>();
        for (String code : channels.keySet()) {
            result.put(code, statusOf(code));
        }
        for (String code : availability.keySet()) {
            result.put(code, statusOf(code));
        }
        return result;
    }

    /**
     * 校验（FR-032）：非法配置直接让 Bean 创建失败，并给出合法取值清单。
     *
     * <p><b>由 {@code SpringChannelRegistry} 在构造末尾调用</b>（而非 {@code @PostConstruct}）：
     * 校验需要「已装配的渠道码」作为输入，而那要等注册表构造完成才拿得到。
     * 若用 {@code @PostConstruct}，两个 Bean 的初始化顺序会决定校验是否拿到码清单——
     * 那是不确定的，会出现「本地过了、CI 没过」的假绿。</p>
     */
    public void validate() {
        List<String> problems = new java.util.ArrayList<>();

        if (channels.isEmpty()) {
            problems.add("payment.routing.channels is empty: at least one channel rule is required "
                    + "(e.g. ALIPAY: { enabled: true, priority: 10 })");
        }

        // 非法 priority（缺失）——非数字在绑定期已由 Spring 抛错
        for (Map.Entry<String, ChannelRule> e : channels.entrySet()) {
            if (e.getValue() == null) {
                problems.add("payment.routing.channels." + e.getKey() + " is null");
                continue;
            }
            if (e.getValue().getPriority() == null) {
                problems.add("payment.routing.channels." + e.getKey()
                        + ".priority is missing (must be an integer; smaller = higher priority)");
            }
        }

        // 未注册渠道码
        if (registeredCodes != null && !registeredCodes.isEmpty()) {
            for (String code : channels.keySet()) {
                if (!registeredCodes.contains(code.toUpperCase())) {
                    problems.add("payment.routing.channels." + code
                            + " is not a registered channel; registered: " + registeredCodes);
                }
            }
            for (String code : availability.keySet()) {
                if (!registeredCodes.contains(code.toUpperCase())) {
                    problems.add("payment.routing.availability." + code
                            + " is not a registered channel; registered: " + registeredCodes);
                }
            }
        }

        // 全部渠道 enabled=false：路由必然无解，属配置事故（FR-032）
        if (!channels.isEmpty() && channels.values().stream().noneMatch(ChannelRule::isEnabled)) {
            problems.add("all channels are enabled=false under payment.routing.channels: "
                    + "auto-routing would always fail (NO_AVAILABLE_CHANNEL); "
                    + "enable at least one channel or set payment.routing.enabled=false");
        }

        // availability.status 缺失
        for (Map.Entry<String, Availability> e : availability.entrySet()) {
            if (e.getValue() == null || e.getValue().getStatus() == null) {
                problems.add("payment.routing.availability." + e.getKey()
                        + ".status is missing; legal values: "
                        + java.util.Arrays.toString(AvailabilityStatus.values()));
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("invalid payment.routing configuration:\n - "
                    + String.join("\n - ", problems));
        }
    }
}
