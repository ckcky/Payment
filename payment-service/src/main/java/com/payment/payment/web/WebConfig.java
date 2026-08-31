package com.payment.payment.web;

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
 *   <li>{@link ChannelCallbackSignatureFilter}：渠道回调验签（<b>ADR-0025 本期为空实现</b>），
 *       注册为 {@code FilterRegistrationBean} 而非普通 {@code Filter} bean——MockMvc 只收集前者，
 *       否则集成测试会绕过过滤器，出现「测试全绿、生产行为不一致」的假绿。</li>
 *   <li>{@link ResolveAuthorizationInterceptor}：{@code /payments/{id}/resolve} 人工收敛端点的
 *       {@code X-Admin-Token} 鉴权（F2 修复）。</li>
 *   <li>{@link InternalServiceAuthInterceptor}：{@code /internal/**} 内部端点鉴权
 *       （<b>ADR-0024 / 0034~0037 本期为空实现</b>），回调路径除外。</li>
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
     * 渠道回调验签过滤器（当前验签为空实现，见 {@link ChannelCallbackSignatureFilter}）。
     * 顺序取最高优先级，确保在任何业务过滤器之前完成准入。
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
                .addPathPatterns("/payments/*/resolve");
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**")
                .excludePathPatterns(ChannelCallbackSignatureFilter.CALLBACK_PATH_PATTERN);
    }
}
