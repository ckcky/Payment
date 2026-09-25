package com.payment.channelgateway.infra.wechat;

import com.payment.channelgateway.application.spi.ChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ChannelPluginFactory;
import org.springframework.stereotype.Component;

/**
 * 微信插件工厂（spec 039 / FR-001 / FR-015）——照 {@code StripeChannelPluginFactory} 同构。
 *
 * <p>把「<b>造</b>」与「<b>用</b>」分开：工厂负责装配 SDK 网关与配置，插件本体只管渠道语义。</p>
 *
 * <h3>注册方式（C-3 裁决 / FR-015）</h3>
 * <b>仅</b>注册为 Spring {@code @Component}，由 {@code ChannelPluginFactoryLocator} 收集。
 * <b>MUST NOT</b> 额外声明 {@code META-INF/services/…spi.ChannelPluginFactory}——
 * 该 ServiceLoader 通道是给<b>外部 jar 插件</b>用的；同进程双路注册是冗余的，
 * 且一旦两路的 {@code code} 相同就会触发启动期「duplicate channel plugin factory」硬失败。</p>
 *
 * <h3>{@code enabled} 不参与注册（C-1 裁决 / FR-016）</h3>
 * 本工厂<b>无条件</b>注册，<b>MUST NOT</b> 加 {@code @ConditionalOnProperty}：
 * {@code enabled} 只门控真实模式（{@link WechatChannelPlugin#isRealModeEnabled()}）。
 * 条件装配会让 {@code enabled=false}（默认值）时 WECHAT 从渠道清单里消失，
 * 连带废掉它的 MOCK 模态，并弄红 {@code scenario-routing.sh} 的「已注册 WECHAT」硬断言。</p>
 */
@Component
public class WechatChannelPluginFactory implements ChannelPluginFactory {

    private final WechatPayProperties properties;

    public WechatChannelPluginFactory(WechatPayProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChannelPluginDescriptor descriptor() {
        return WechatChannelPlugin.DESCRIPTOR;
    }

    @Override
    public ChannelPlugin create() {
        return new WechatChannelPlugin(new WechatSdkGateway(properties), properties);
    }
}
