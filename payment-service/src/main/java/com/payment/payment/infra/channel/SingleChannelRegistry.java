package com.payment.payment.infra.channel;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.PaymentChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 单通道内存注册表（Feature 028 / FR-036 兼容垫片，供测试与兼容构造使用）。
 *
 * <p>语义与 {@code PaymentApplicationService} 的「单通道注册表」一致：只认一个渠道码，
 * 未知码抛 {@code INVALID_ARGUMENT}；{@code registeredCodes()} 返回该单码。
 * 用于「既有测试只关心一个渠道」的场景——无需拉起多个 Adapter 即可走通反向路径。</p>
 */
public final class SingleChannelRegistry implements ChannelRegistry {

    private final String code;
    private final PaymentChannel channel;
    private final Set<String> codes;

    public SingleChannelRegistry(PaymentChannel channel) {
        this.channel = channel;
        this.code = channel.channelCode() == null ? "MOCK" : channel.channelCode().toUpperCase();
        Set<String> set = new TreeSet<>();
        set.add(this.code);
        this.codes = Set.copyOf(set);
    }

    /** 便捷构造：按显式渠道码建，用于「渠道码与实现解耦」的测试。 */
    public static SingleChannelRegistry of(String channelCode, PaymentChannel channel) {
        return new SingleChannelRegistry(channelCode, channel);
    }

    private SingleChannelRegistry(String channelCode, PaymentChannel channel) {
        this.channel = channel;
        this.code = channelCode == null ? "MOCK" : channelCode.toUpperCase();
        Set<String> set = new TreeSet<>();
        set.add(this.code);
        this.codes = Set.copyOf(set);
    }

    @Override
    public PaymentChannel resolve(String channelCode) {
        if (channelCode == null || !code.equals(channelCode.trim().toUpperCase())) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "unknown channelCode '" + channelCode + "'; registered channels: " + codes);
        }
        return channel;
    }

    @Override
    public Set<String> registeredCodes() {
        return codes;
    }
}
