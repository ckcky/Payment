package com.payment.common.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * {@link BusinessMetrics} 的 Micrometer 实现（plan T070 / Phase 6）。
 *
 * <p>通过 {@code MeterRegistry} 把业务事实落到 Micrometer：计数用 {@link Counter}，耗时用
 * {@link Timer}。当上下文中不存在 {@code MeterRegistry}（无 actuator / 无 Micrometer）时，由
 * 自动配置回退到 {@link NoopBusinessMetrics}。</p>
 */
public class MicrometerBusinessMetrics implements BusinessMetrics {

    private final MeterRegistry registry;

    public MicrometerBusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void counter(String name, double value, String... tags) {
        registry.counter(name, tags).increment(value);
    }

    @Override
    public void timer(String name, Duration duration, String... tags) {
        registry.timer(name, tags).record(duration);
    }
}
