package com.payment.common.core.config;

import com.payment.common.core.error.GlobalExceptionHandler;
import com.payment.common.core.event.DomainEventDispatcher;
import com.payment.common.core.event.DomainEventHandler;
import com.payment.common.core.event.DomainEventPublisher;
import com.payment.common.core.event.InMemoryOutboxStore;
import com.payment.common.core.event.OutboxPublisher;
import com.payment.common.core.event.OutboxRelay;
import com.payment.common.core.event.OutboxStore;
import com.payment.common.core.idempotency.IdempotencyRegistry;
import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.common.core.trace.TraceIdFilter;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * common-core 的自动配置：把共享的 {@link GlobalExceptionHandler}、{@link TraceIdFilter} 与
 * 事务性 Outbox（{@link OutboxStore}/{@link OutboxPublisher}/{@link OutboxRelay}）注册到各服务
 * （各服务默认只扫描自身包，不扫描 com.payment.common，故用自动配置装配）。
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
    public OutboxStore outboxStore() {
        return new InMemoryOutboxStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventPublisher domainEventPublisher(OutboxStore store) {
        return new OutboxPublisher(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventDispatcher domainEventDispatcher(List<DomainEventHandler<?>> handlers) {
        return new DomainEventDispatcher(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxStore store, DomainEventDispatcher dispatcher) {
        return new OutboxRelay(store, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyRegistry idempotencyRegistry() {
        return new InMemoryIdempotencyRegistry();
    }
}
