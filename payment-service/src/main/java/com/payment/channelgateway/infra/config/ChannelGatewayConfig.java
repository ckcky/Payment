package com.payment.channelgateway.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 渠道网关域配置装配（spec 041）。
 *
 * <p>{@link MockCashierProperties} 随演示收银台派发策略一并迁入本域后，
 * 其 {@code @EnableConfigurationProperties} 注册也 MUST 从 {@code payment/web/WebConfig}
 * 迁出——否则「渠道域的配置由 Payment 的 web 层注册」这个反向关系会一直留着。
 * 本类让渠道域的 Bean 与它的配置自洽。</p>
 */
@Configuration
@EnableConfigurationProperties(MockCashierProperties.class)
public class ChannelGatewayConfig {
}
