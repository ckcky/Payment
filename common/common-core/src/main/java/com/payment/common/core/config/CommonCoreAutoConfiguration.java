package com.payment.common.core.config;

import com.payment.common.core.accesslog.AccessLogFilter;
import com.payment.common.core.accesslog.AccessLogProperties;
import com.payment.common.core.accesslog.PassThroughBodyMasker;
import com.payment.common.core.accesslog.SensitiveBodyMasker;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * common-core 的自动配置：把共享的 {@link GlobalExceptionHandler}、{@link TraceIdFilter}、
 * 访问日志 {@link AccessLogFilter} 与 {@link IdempotencyRegistry} 注册到各服务
 * （各服务默认只扫描自身包，不扫描 com.payment.common，故用自动配置装配）。
 *
 * <p>过滤器定序（spec 021 / FR-002，ADR-0068）：{@code TraceIdFilter} order=-200（先写 MDC）→
 * {@code AccessLogFilter} order=-100（拿得到 traceId，早于业务 Filter）。都用
 * {@link FilterRegistrationBean} 显式注册——裸 Filter Bean 会被 Boot 以默认最低优先级
 * 再注册一次，且顺序不可控。</p>
 *
 * <p>跨服务通信统一走公开同步 HTTP/RPC，本模块不提供跨服务事件或 Outbox 机制
 * （Constitution §IV / plan §5）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(AccessLogProperties.class)
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

    /** TraceIdFilter 显式定序注册（order=-200）：保证 AccessLogFilter 与业务执行时 MDC 已有 traceId。 */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter traceIdFilter) {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(traceIdFilter);
        registration.setOrder(-200);
        return registration;
    }

    /** 脱敏桩（D3）：透传实现，服务可覆盖为真脱敏实现，Filter 零改动。 */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveBodyMasker sensitiveBodyMasker() {
        return new PassThroughBodyMasker();
    }

    /**
     * AccessLogFilter 注册（order=-100，spec 021 / FR-002）：{@code common.access-log.enabled=false}
     * 时不装配（AC1.3，matchIfMissing=true 默认开启）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "common.access-log", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(
            AccessLogProperties properties, SensitiveBodyMasker masker) {
        FilterRegistrationBean<AccessLogFilter> registration =
                new FilterRegistrationBean<>(new AccessLogFilter(properties, masker));
        registration.setOrder(-100);
        return registration;
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
}
