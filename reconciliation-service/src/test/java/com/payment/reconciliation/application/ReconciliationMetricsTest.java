package com.payment.reconciliation.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账业务指标（T072）：一次执行记一次 {@code reconciliation.run}，每个差异记一次
 * {@code reconciliation.difference}（按差异类型打 type 标签）。
 */
class ReconciliationMetricsTest {

    @Test
    void recordsRunAndDifferenceMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();

        PaymentFactsClient payments = () -> List.of(
                new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"));
        RefundFactsClient refunds = () -> List.of();
        ChannelStatementLoader loader = period -> List.of(
                new ChannelStatement("mock-ref-1", 1000L, "CNY", "SUCCEEDED"),
                new ChannelStatement("channel-extra-1", 999L, "CNY", "SUCCEEDED"),
                new ChannelStatement("channel-extra-2", 998L, "CNY", "SUCCEEDED"));

        ReconciliationApplicationService service = new ReconciliationApplicationService(
                repository, payments, refunds, loader, metrics);

        ReconciliationBatch batch = service.runReconciliation("2026-08");

        assertThat(batch.getDifferences()).hasSize(2);
        assertThat(batch.getDifferences()).extracting("type")
                .containsOnly(DifferenceType.CHANNEL_ONLY);

        assertThat(registry.get("reconciliation.run").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("reconciliation.difference").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("reconciliation.difference").tag("type", DifferenceType.CHANNEL_ONLY.name())
                .counter().count()).isEqualTo(2.0);
    }
}
