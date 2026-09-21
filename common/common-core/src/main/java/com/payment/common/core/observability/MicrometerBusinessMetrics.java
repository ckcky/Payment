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

    /** gauge 回调强引用集：Micrometer 对注册对象仅持 WeakReference，须由本单例保活，否则回调被 GC 后 gauge 恒 NaN。 */
    private final java.util.Set<Object> gaugeStateRefs = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    @Override
    public void gauge(String name, java.util.function.Supplier<Number> valueSupplier, String... tags) {
        // Micrometer 同名同标签 gauge 幂等：重复注册返回既有仪表，不会堆积。
        gaugeStateRefs.add(valueSupplier); // 保活（见字段注释），不参与业务语义
        io.micrometer.core.instrument.Gauge.builder(name, valueSupplier, s -> {
                    Number v = s.get();
                    return v == null ? Double.NaN : v.doubleValue(); // null（如抓取期 DB 抖动）→ NaN，不抛
                })
                .tags(tags)
                .register(registry);
    }
}
