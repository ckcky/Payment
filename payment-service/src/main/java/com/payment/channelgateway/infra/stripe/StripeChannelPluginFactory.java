package com.payment.channelgateway.infra.stripe;

import com.payment.channelgateway.application.spi.ChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ChannelPluginFactory;
import org.springframework.stereotype.Component;

/**
 * Stripe 插件工厂（渠道插件化 / STRIPE-05）。
 *
 * <p>把「<b>造</b>」与「<b>用</b>」分开：工厂负责装配 SDK 网关与配置，
 * 插件本体只管渠道语义。好处有三：</p>
 * <ul>
 *   <li><b>描述符不需要创建实例</b>：启动期的渠道码校验与路由预览只读元数据，
 *       不会被迫实例化一个「没启用也要连 Stripe」的插件；</li>
 *   <li><b>单测可脱离 Spring</b>：直接 {@code new StripeChannelPlugin(fakeGateway, props)}；</li>
 *   <li><b>装配可替换</b>：把 {@code StripeSdkGateway} 换成纯 JDK 实现，只改 {@link #create()} 一行。</li>
 * </ul>
 *
 * <p>本工厂注册为 Spring Bean，由 {@code ChannelPluginFactoryLocator} 收集；
 * 同时它也是标准 Java SPI——外部 jar 在
 * {@code META-INF/services/…spi.ChannelPluginFactory} 声明同一实现类即可免编译接入。</p>
 */
@Component
public class StripeChannelPluginFactory implements ChannelPluginFactory {

    private final StripeSandboxProperties properties;

    public StripeChannelPluginFactory(StripeSandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChannelPluginDescriptor descriptor() {
        return StripeChannelPlugin.DESCRIPTOR;
    }

    @Override
    public ChannelPlugin create() {
        return new StripeChannelPlugin(new StripeSdkGateway(properties), properties);
    }
}
