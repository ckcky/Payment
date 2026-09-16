package com.payment.payment.application.channel;

import java.util.Set;

/**
 * 渠道注册表（Feature 028 / FR-015，ADR-0073）：
 * 按渠道码精确寻址已装配的 {@link PaymentChannel} 实现。
 *
 * <p><b>两条使用路径，语义完全不同</b>：
 * <ul>
 *   <li><b>正向（建单）</b>：{@code ChannelRouter} 选出一个码，再由本表 {@link #resolve} 取实现；</li>
 *   <li><b>反向（退款 / 重试 / 主动查询）</b>：按 {@code payment_attempts.channel_code} <b>已记录</b>
 *       的码解析实现（INV-6）——<b>禁止调 Router</b>。资金安全红线：退款换渠道 = 钱退错地方。</li>
 * </ul>
 *
 * <p><b>为什么要有注册表（D4）</b>：spec 015 §8 曾写「不做 {@code Map<ChannelCode, PaymentChannel>}
 * 注册表」——那是因为当时只有一个渠道，Map 是过度设计。现在有三个渠道实现，没有注册表就只剩
 * 「按 {@code instanceof} 或 {@code if-else} 分发」这条更差的路。本决策已由 ADR-0073 正式取代。</p>
 */
public interface ChannelRegistry {

    /**
     * 按渠道码解析渠道实现（大小写不敏感，返回的码为大写）。
     *
     * @param channelCode 渠道码（不可为空）
     * @return 已装配的渠道实现
     * @throws com.payment.common.core.error.BizException {@code INVALID_ARGUMENT}
     *         —— 未注册，错误信息<b>列出已注册渠道清单</b>（FR-029：不静默回落）
     */
    PaymentChannel resolve(String channelCode);

    /** 当前已注册的全部渠道码（大写，不可变集合，便于启动期校验与错误信息拼装）。 */
    Set<String> registeredCodes();
}
