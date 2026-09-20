package com.payment.common.core.config;

import com.payment.common.core.client.TraceIdRequestInterceptor;
import com.payment.common.core.dye.DyeRequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Feign traceId 传播自动配置：仅当类路径存在 {@code feign.RequestInterceptor}（即服务实际引入
 * OpenFeign）时才装配 {@link TraceIdRequestInterceptor}。
 *
 * <p>独立于 {@link CommonCoreAutoConfiguration}，并用<strong>类级</strong>
 * {@link ConditionalOnClass} 门控：无 Feign 的服务（如 merchant/catalog）整类跳过，
 * 避免其 bean 类型推导因缺失 {@code feign.RequestInterceptor} 而失败。</p>
 */
@AutoConfiguration
@ConditionalOnClass(feign.RequestInterceptor.class)
public class FeignTraceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdRequestInterceptor traceIdRequestInterceptor() {
        return new TraceIdRequestInterceptor();
    }

    /**
     * 染色出站拦截器（spec 030 / FR-163~FR-164）：把当前模态写进 {@code X-Dye-Tag} 供下游读取。
     *
     * <p>INV-5：本 Bean 与 {@link DyeFilter}（入站）**必须同批落地**——只加入站不做出站，
     * 下游读不到染色 ⇒ 跨服务沙箱链路断掉（{@code InternalToken} 全线 403 的历史教训）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public DyeRequestInterceptor dyeRequestInterceptor() {
        return new DyeRequestInterceptor();
    }
}
