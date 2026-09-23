package com.payment.payment.application.channel.spi;

import com.payment.payment.application.channel.PaymentScene;

import java.util.Set;

/**
 * 渠道插件的<b>自描述元数据</b>（渠道插件化内核 / SPI-01）。
 *
 * <p><b>为什么要有描述符</b>：注册表以前只认一个 {@code channelCode()} 字符串，
 * 于是「这个渠道支持哪些场景」「它有没有真实模式」「它的回调挂在哪个路径」这些<b>能力问题</b>
 * 只能靠 {@code instanceof} 或 {@code if (code.equals("ALIPAY"))} 回答——
 * 那就是把渠道差异写回内核。描述符让插件<b>自己声明能力</b>，内核只做泛型调度。</p>
 *
 * <h3>与 {@code PaymentChannel} 既有方法的关系</h3>
 * {@code PaymentChannel#supportedScenes()} / {@code #supportsRealMode()} 是既有契约，
 * 本描述符<b>不重复定义</b>，只补充注册表与运维面必需的两项：
 * 展示名与回调路径。{@link #supportedScenes()} / {@link #supportsRealMode()}
 * 由 {@link ChannelPlugin} 的默认实现从本描述符派生，保证单一事实源。</p>
 *
 * @param code          渠道码（大写、非空、全局唯一），与 {@code PaymentChannel#channelCode()} 同源
 * @param displayName   人类可读名称（日志 / 运维面用，不参与任何路由判定）
 * @param supportedScenes 本插件支持的支付场景（真实渠道按真实能力收窄）
 * @param supportsRealMode 是否具备真实渠道模式（mock-only 插件为 {@code false}）
 * @param callbackPath  异步回调挂载路径（相对 {@code /internal/channels} 的子路径段；
 *                      {@code null} 表示本插件不接收渠道回调，如纯 mock）
 */
public record ChannelPluginDescriptor(String code,
                                      String displayName,
                                      Set<PaymentScene> supportedScenes,
                                      boolean supportsRealMode,
                                      String callbackPath) {

    public ChannelPluginDescriptor {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("descriptor.code must be non-blank");
        }
        if (supportedScenes == null || supportedScenes.isEmpty()) {
            throw new IllegalArgumentException(
                    "descriptor.supportedScenes must be non-empty for channel '" + code + "'");
        }
    }

    /** 无回调能力的插件（如纯 mock / 仅主动查询型渠道）。 */
    public static ChannelPluginDescriptor withoutCallback(String code, String displayName,
                                                          Set<PaymentScene> supportedScenes,
                                                          boolean supportsRealMode) {
        return new ChannelPluginDescriptor(code, displayName, supportedScenes, supportsRealMode, null);
    }
}
