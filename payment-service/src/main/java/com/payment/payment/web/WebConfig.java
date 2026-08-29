package com.payment.payment.web;

import com.payment.common.core.observability.BusinessMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册安全守卫（Feature 009 / ADR-0024~0025）。
 *
 * <ul>
 *   <li>{@link ChannelCallbackSignatureFilter}：渠道回调 HMAC 验签，注册为
 *       {@code FilterRegistrationBean} 而非普通 {@code Filter} bean——MockMvc 只收集前者，
 *       否则集成测试会绕过验签，出现「测试全绿、生产裸奔」的假绿。</li>
 *   <li>{@link ResolveAuthorizationInterceptor}：{@code /payments/{id}/resolve} 人工收敛端点的
 *       {@code X-Admin-Token} 鉴权（F2 修复）。</li>
 *   <li>{@link InternalServiceAuthInterceptor}：{@code /internal/**} 内部端点的
 *       {@code X-Service-Token} 鉴权，回调路径除外（其由验签过滤器独立守卫）。</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 渠道回调的 Servlet 前缀匹配模式（具体路径由过滤器内部再判定）。 */
    static final String CHANNEL_CALLBACK_PREFIX = "/internal/payments/*";

    private final ResolveAuthorizationInterceptor resolveInterceptor;
    private final InternalServiceAuthInterceptor internalAuthInterceptor;

    public WebConfig(ResolveAuthorizationInterceptor resolveInterceptor,
                     InternalServiceAuthInterceptor internalAuthInterceptor) {
        this.resolveInterceptor = resolveInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
    }

    /**
     * 渠道回调验签过滤器：常开、不可关闭；密钥缺失时自身返回 503。
     * 顺序取最高优先级，确保在任何业务过滤器之前完成准入。
     */
    @Bean
    public FilterRegistrationBean<ChannelCallbackSignatureFilter> channelCallbackSignatureFilter(
            @Value("${payment.security.channel-secret:}") String channelSecret,
            @Value("${payment.security.signature-replay-window-ms:300000}") long replayWindowMs,
            BusinessMetrics metrics) {
        FilterRegistrationBean<ChannelCallbackSignatureFilter> registration = new FilterRegistrationBean<>(
                new ChannelCallbackSignatureFilter(channelSecret, replayWindowMs, metrics));
        registration.addUrlPatterns(CHANNEL_CALLBACK_PREFIX);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(resolveInterceptor)
                .addPathPatterns("/payments/*/resolve");
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**")
                .excludePathPatterns(ChannelCallbackSignatureFilter.CALLBACK_PATH_PATTERN);
    }
}
