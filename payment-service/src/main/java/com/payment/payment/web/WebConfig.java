package com.payment.payment.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.payment.channel.infra.config.RoutingProperties;

import com.payment.channel.web.ChannelCallbackSignatureFilter;
/**
 * Web 层配置：注册安全守卫（Feature 009 / ADR-0024 / ADR-0025）。
 *
 * <ul>
 *   <li>{@link ChannelCallbackSignatureFilter}：渠道回调验签（<b>ADR-0025 本期为空实现（占位）</b>，
 *       {@code verifySignature} 恒返回 {@code true}，回调一律放行）。以 {@code FilterRegistrationBean}
 *       显式注册而非普通 {@code Filter} bean——MockMvc 只收集前者，否则集成测试会绕过过滤器，
 *       出现「测试全绿、生产行为不一致」的假绿。</li>
 *   <li>{@link ResolveAuthorizationInterceptor}：{@code /payments/{id}/resolve} 人工收敛端点的
 *       {@code X-Admin-Token} 鉴权（F2 修复）。</li>
 *   <li>{@link InternalServiceAuthInterceptor}：{@code /internal/**} 内部端点鉴权
 *       （<b>ADR-0024 / 0034~0037 本期为空实现</b>），回调路径除外。</li>
 * </ul>
 *
 * <p>另启用 {@link RoutingProperties}（Feature 028 / ADR-0073：渠道路由与可用性配置）。
 * （{@code MockCashierProperties} 已于 spec 041 随演示收银台派发策略迁入渠道网关域，
 * 注册点见 {@code com.payment.channel.infra.config.ChannelGatewayConfig}。）</p>
 */
@Configuration
@EnableConfigurationProperties({RoutingProperties.class})
public class WebConfig implements WebMvcConfigurer {

    /**
     * 唯一回调入口的 Servlet 前缀匹配模式（spec 041 / T12；具体路径由过滤器内部再判定）。
     *
     * <p>T12 之前这里是两条前缀（{@code /internal/payments/*} 与
     * {@code /internal/payments/refunds/*}）；两个端点删除后合并为一条。</p>
     */
    static final String CHANNEL_CALLBACK_PREFIX = "/callbacks/*";

    private final ResolveAuthorizationInterceptor resolveInterceptor;
    private final InternalServiceAuthInterceptor internalAuthInterceptor;

    public WebConfig(ResolveAuthorizationInterceptor resolveInterceptor,
                     InternalServiceAuthInterceptor internalAuthInterceptor) {
        this.resolveInterceptor = resolveInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
    }

    /**
     * 渠道回调验签过滤器（ADR-0025 占位空实现，恒放行，见 {@link ChannelCallbackSignatureFilter}）。
     * 顺序取最高优先级，确保在任何业务过滤器之前完成准入判定（本期放行，骨架保留）。
     */
    @Bean
    public FilterRegistrationBean<ChannelCallbackSignatureFilter> channelCallbackSignatureFilter() {
        FilterRegistrationBean<ChannelCallbackSignatureFilter> registration =
                new FilterRegistrationBean<>(new ChannelCallbackSignatureFilter());
        registration.addUrlPatterns(CHANNEL_CALLBACK_PREFIX);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(resolveInterceptor)
                .addPathPatterns("/payments/*/resolve")
                // spec 034 / T9：台账人工重放端点与 resolve 同守卫（X-Admin-Token）
                .addPathPatterns("/internal/payments/pending-postings/**");
        // spec 041 / T12：唯一回调入口 /callbacks/channels/** 不在 /internal/** 之下，
        // 因此天然不受内部服务鉴权管辖——原先的两条 excludePathPatterns 随之删除
        // （它们排除的 /internal/payments/** 回调路径已不存在）。
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**");
    }
}
