package com.payment.reconciliation.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.reconciliation.infra.CsvChannelStatementLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账单回退留痕（spec 006 T013 / FR-018）：回退 MUST 递增 {@code reconciliation.statement_fallback}
 * 且带 period 维度——「凭空少了一份账单」是资金事故信号，必须可被告警捕获。
 */
class ReconciliationStatementFallbackMetricsTest {

    private static final String FIXTURE_DIR = "fixtures/channel-statements";

    @Test
    void fallbackIncrementsMetricWithPeriodTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
        CsvChannelStatementLoader loader =
                new CsvChannelStatementLoader(FIXTURE_DIR, "sample.csv", "", metrics);

        assertThat(loader.load("2026-08-31").source().fallbackUsed()).isFalse();
        assertThat(registry.find("reconciliation.statement_fallback").counter()).isNull();

        assertThat(loader.load("2099-01").source().fallbackUsed()).isTrue();
        assertThat(registry.find("reconciliation.statement_fallback")
                .tag("period", "2099-01")
                .counter())
                .isNotNull()
                .extracting(c -> c.count())
                .isEqualTo(1.0);
    }

    @Test
    void repeatedFallbackAccumulates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
        CsvChannelStatementLoader loader =
                new CsvChannelStatementLoader(FIXTURE_DIR, "sample.csv", "", metrics);

        loader.load("2099-01");
        loader.load("2099-02");
        double total = registry.find("reconciliation.statement_fallback").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();

        assertThat(total).isEqualTo(2.0);
    }

    @Test
    void hitDoesNotIncrementFallback() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
        CsvChannelStatementLoader loader =
                new CsvChannelStatementLoader(FIXTURE_DIR, "sample.csv", "", metrics);

        loader.load("2026-08-31");

        assertThat(registry.find("reconciliation.statement_fallback").counters()).isEmpty();
    }
}
