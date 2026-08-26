package com.payment.common.core.config;

import com.payment.common.core.client.TraceIdRequestInterceptor;
import com.payment.common.core.error.GlobalExceptionHandler;
import com.payment.common.core.idempotency.IdempotencyRegistry;
import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.core.trace.TraceIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * common-core 的自动配置：把共享的 {@link GlobalExceptionHandler}、{@link TraceIdFilter} 与
 * {@link IdempotencyRegistry} 注册到各服务（各服务默认只扫描自身包，不扫描 com.payment.common，
 * 故用自动配置装配）。
 *
 * <p>跨服务通信统一走公开同步 HTTP/RPC，本模块不提供跨服务事件或 Outbox 机制
 * （Constitution §IV / plan §5）。</p>
 */
@AutoConfiguration
public class CommonCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyRegistry idempotencyRegistry() {
        return new InMemoryIdempotencyRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics businessMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? new NoopBusinessMetrics() : new MicrometerBusinessMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public StructuredAuditLogger structuredAuditLogger() {
        return new StructuredAuditLogger();
    }

    @Bean
    @ConditionalOnClass(feign.RequestInterceptor.class)
    @ConditionalOnMissingBean
    public TraceIdRequestInterceptor traceIdRequestInterceptor() {
        return new TraceIdRequestInterceptor();
    }
}
