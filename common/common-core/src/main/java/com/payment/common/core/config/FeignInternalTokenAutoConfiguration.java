package com.payment.common.core.config;

import com.payment.common.core.client.InternalTokenRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Feign 出站内部服务令牌自动配置（ADR-0034）。
 *
 * <p>与 {@link FeignTraceAutoConfiguration} 同构：用<strong>类级</strong>
 * {@link ConditionalOnClass} 门控，未引入 OpenFeign 的服务（merchant / catalog / entitlement）
 * 整类跳过，避免 bean 类型推导因缺失 {@code feign.RequestInterceptor} 而失败。</p>
 *
 * <p>默认关闭（{@code platform.security.outbound-token-enabled=false}），因此本配置的存在本身
 * 不改变任何既有调用行为；只有在显式打开开关并注入 {@code PLATFORM_INTERNAL_TOKEN} 后才生效。</p>
 */
@AutoConfiguration
@ConditionalOnClass(feign.RequestInterceptor.class)
public class FeignInternalTokenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InternalTokenRequestInterceptor internalTokenRequestInterceptor(
            @Value("${platform.security.outbound-token-enabled:false}") boolean enabled,
            @Value("${platform.security.internal-token:}") String serviceToken) {
        return new InternalTokenRequestInterceptor(enabled, serviceToken);
    }
}
