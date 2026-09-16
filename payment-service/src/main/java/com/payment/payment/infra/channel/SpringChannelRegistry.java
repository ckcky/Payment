package com.payment.payment.infra.channel;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.infra.config.RoutingProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 渠道注册表实现（Feature 028 / FR-018，ADR-0073）：
 * <b>构造注入 {@code List<PaymentChannel>}</b>，建不可变 Map，启动期校验 code 非空且唯一。
 *
 * <p><b>为什么用 {@code List} 注入而不是手写 {@code Map}</b>：手写 Map 的失败模式是
 * 「加了渠道忘了注册」→ 运行期 500，且只在走到那条路径时才暴露；{@code List} 注入是
 * 「Spring 有多少渠道 Bean 就自动注册多少」——<b>编译期就有、启动期就炸</b>。</p>
 *
 * <p><b>重复 code 直接让 Bean 创建失败</b>（FR-018 / ADR-0049 第 2 条精神）：两个 Adapter 声明
 * 同一身份是结构性错误，注册表若静默覆盖，会变成「路由选了 A、实际调用 B」的幽灵缺陷。</p>
 *
 * <p>构造末尾把已注册码回填给 {@link RoutingProperties}，使「配置里出现未注册渠道码」这类
 * 非法配置能在启动期被拒（FR-032）。</p>
 */
@Component
public class SpringChannelRegistry implements ChannelRegistry {

    private final Map<String, PaymentChannel> byCode;

    public SpringChannelRegistry(List<PaymentChannel> channels, RoutingProperties properties) {
        Map<String, PaymentChannel> map = new LinkedHashMap<>();
        for (PaymentChannel channel : channels) {
            String code = channel.channelCode();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException(
                        "channel adapter " + channel.getClass().getName()
                                + " returned a blank channelCode(); channelCode() must be non-blank (ADR-0072 / FR-001)");
            }
            String key = code.trim().toUpperCase();
            PaymentChannel previous = map.put(key, channel);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate channelCode '" + key + "': declared by both "
                                + previous.getClass().getName() + " and " + channel.getClass().getName()
                                + "; channel codes must be globally unique (ADR-0072 / FR-018)");
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException(
                    "no PaymentChannel beans found; at least one channel adapter must be on the classpath");
        }
        this.byCode = Map.copyOf(map);
        // FR-032：回填已注册码 → 触发配置强校验。校验必须在注册表构造内调用：
        // 「配置里的渠道码是否已注册」这个判断需要注册结果，用 @PostConstruct 会依赖 Bean 初始化顺序。
        properties.setRegisteredCodes(registeredCodes());
        properties.validate();
    }

    @Override
    public PaymentChannel resolve(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "channelCode is required; registered channels: " + registeredCodes());
        }
        PaymentChannel channel = byCode.get(channelCode.trim().toUpperCase());
        if (channel == null) {
            // FR-029：错误信息必须列出已注册渠道清单——不静默回落
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "unknown channelCode '" + channelCode + "'; registered channels: " + registeredCodes());
        }
        return channel;
    }

    @Override
    public Set<String> registeredCodes() {
        return new TreeSet<>(byCode.keySet()); // 字典序，保证错误信息与断言确定性
    }
}
