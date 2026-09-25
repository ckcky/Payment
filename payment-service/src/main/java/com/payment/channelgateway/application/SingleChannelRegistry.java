package com.payment.channelgateway.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.Set;
import java.util.TreeSet;

/**
 * 单通道内存注册表（Feature 028 / FR-036 兼容垫片，供测试与兼容构造使用）。
 *
 * <p>语义与 {@link ChannelRegistry} 的多通道实现一致：只认一个渠道码，未知码抛
 * {@code INVALID_ARGUMENT}；{@code registeredCodes()} 返回该单码。用于「既有测试只关心
 * 一个渠道」的场景——无需拉起多个 Adapter 即可走通反向路径。</p>
 *
 * <p><b>落点（2026-09-20 边界复审 FIX-1）</b>：本类原先放在
 * {@code com.payment.channelgateway.infra}，导致 {@code application..} 主体的三处兼容构造
 * 反向依赖 {@code infra.channel..}——而 INV-4 的 ArchUnit 门禁当时只覆盖
 * {@code application.channel..}，这三处穿透因此长期逃过门禁（名义合规、实际穿透）。</p>
 *
 * <p>本类<b>零 infra 依赖</b>（只用 {@link ChannelRegistry} / {@link PaymentChannel} 两个
 * 应用层类型 + {@code common-core} 的异常与码），它是「注册表」这个<b>应用层抽象</b>的一个
 * 退化实现，不是任何基础设施适配器——故归位 {@code application.channel}。这样依赖方向
 * 仍是 {@code infra.channel → application.channel}，门禁也能覆盖整个 {@code application..}。</p>
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
