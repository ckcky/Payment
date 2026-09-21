package com.payment.settlement.posting.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 台账人工入口的 MVC 拦截器注册（spec 034 §9.1）：仅守卫 pending-postings 路径，
 * 不触碰既有服务路由。
 */
@Configuration
public class PostingWebConfig implements WebMvcConfigurer {

    private final PostingAdminGuardInterceptor guard;

    public PostingWebConfig(PostingAdminGuardInterceptor guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/internal/settlements/pending-postings/**");
    }
}
