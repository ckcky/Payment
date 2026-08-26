package com.payment.common.core.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Micrometer 指标落地（T070）：计数与耗时都写入 {@code MeterRegistry}。
 */
class MicrometerBusinessMetricsTest {

    @Test
    void counterAndTimerRecordToRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        metrics.counter("payment.succeeded", 2, "module", "payment");
        metrics.timer("payment.unknown.duration", Duration.ofMillis(50), "module", "payment");

        assertThat(registry.get("payment.succeeded").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("payment.unknown.duration").timer().count()).isEqualTo(1);
    }
}
