package com.payment.common.core.observability;

import java.time.Duration;

/**
 * {@link BusinessMetrics} 的空实现：在无 {@code MeterRegistry} 的上下文（如单元测试、轻量服务）
 * 中静默丢弃指标，避免调用方判空。
 */
public class NoopBusinessMetrics implements BusinessMetrics {

    @Override
    public void counter(String name, double value, String... tags) {
        // 无 MeterRegistry，指标不落地（no-op）。
    }

    @Override
    public void timer(String name, Duration duration, String... tags) {
        // 无 MeterRegistry，指标不落地（no-op）。
    }
}
