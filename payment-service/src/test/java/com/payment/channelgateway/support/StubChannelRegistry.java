package com.payment.channelgateway.support;

import com.payment.channelgateway.application.ChannelRegistry;
import com.payment.channelgateway.application.PaymentChannel;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link ChannelRegistry} 的测试替身（spec 037 / T6）。
 *
 * <p>回调链路的装配需要一张「渠道码 → 渠道实现」的表：{@code ChannelCallbackHandler}
 * 第 ① 步靠它<b>精确寻址</b>（INV-6），而单测里没有 Spring 容器去装配真实的注册表。
 * 迁移前这件事由 {@code AlipayNotifyController} 的存在本身隐式完成（端点路径里就写着
 * 「alipay」），通用端点把它显式化成了一次查表——本替身就是那次查表的测试侧对应物。</p>
 *
 * <p>两处语义与生产实现<b>刻意对齐</b>（不是随手写的桩）：</p>
 * <ul>
 *   <li><b>大小写不敏感</b>——路径变量可能带任意大小写，生产实现按大写归一后查表
 *       （{@code ChannelRegistry#resolve} 的契约如此）；</li>
 *   <li><b>未注册即抛异常、绝不回落</b>（FR-029）——静默回落会让「回调指到了不存在的渠道」
 *       变成一笔悄悄无人处理的资金事件。</li>
 * </ul>
 */
public final class StubChannelRegistry implements ChannelRegistry {

    private final Map<String, PaymentChannel> channels = new LinkedHashMap<>();

    /** 注册一个渠道（返回自身，便于链式装配）。 */
    public StubChannelRegistry register(String channelCode, PaymentChannel channel) {
        channels.put(channelCode.toUpperCase(Locale.ROOT), channel);
        return this;
    }

    @Override
    public PaymentChannel resolve(String channelCode) {
        PaymentChannel channel = channels.get(channelCode == null ? null : channelCode.toUpperCase(Locale.ROOT));
        if (channel == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "channel not registered: " + channelCode + " (registered=" + channels.keySet() + ")");
        }
        return channel;
    }

    @Override
    public Set<String> registeredCodes() {
        return Set.copyOf(channels.keySet());
    }
}
