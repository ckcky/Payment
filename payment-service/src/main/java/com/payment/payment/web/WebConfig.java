package com.payment.payment.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册 {@link ResolveAuthorizationInterceptor} 到 resolve 收敛端点.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ResolveAuthorizationInterceptor resolveInterceptor;

    public WebConfig(ResolveAuthorizationInterceptor resolveInterceptor) {
        this.resolveInterceptor = resolveInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(resolveInterceptor)
                .addPathPatterns("/payments/*/resolve");
    }
}
