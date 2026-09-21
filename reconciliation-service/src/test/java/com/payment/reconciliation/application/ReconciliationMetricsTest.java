package com.payment.reconciliation.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.legacyLine;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.seedImport;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账业务指标（T072）：一次执行记一次 {@code reconciliation.run}，每个差异记一次
 * {@code reconciliation.difference}（按差异类型打标签）。
 */
class ReconciliationMetricsTest {

    @Test
    void recordsRunAndDifferenceMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
        InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();

        PaymentFactsClient payments = period -> List.of(
                new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1"));
        RefundFactsClient refunds = period -> List.of();
        seedImport(imports, "MOCK", "2026-08", List.of(
                legacyLine(1, "mock-ref-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "channel-extra-1", 999L, "SUCCEEDED"),
                legacyLine(3, "channel-extra-2", 998L, "SUCCEEDED")));

        ReconciliationApplicationService service = service(repository, imports, payments, refunds,
                metrics, new StructuredAuditLogger());

        ReconciliationBatch batch = service.runReconciliation("2026-08");

        assertThat(batch.getDifferences()).hasSize(2);
        assertThat(batch.getDifferences()).extracting("type")
                .containsOnly(DifferenceType.CHANNEL_ONLY);

        assertThat(registry.get("reconciliation.run").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("reconciliation.difference").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("reconciliation.difference").tag("kind", DifferenceType.CHANNEL_ONLY.name())
                .counter().count()).isEqualTo(2.0);
    }

    /**
     * 差异金额口径（spec 006 T037/T040 / N2）：单侧缺失取该侧金额。
     * 本场景 2 条 CHANNEL_ONLY（999 + 998）⇒ 1997 分。
     */
    @Test
    void recordsDifferenceAmountMinor() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
        seedImport(imports, "MOCK", "2026-08", List.of(
                legacyLine(1, "mock-ref-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "channel-extra-1", 999L, "SUCCEEDED"),
                legacyLine(3, "channel-extra-2", 998L, "SUCCEEDED")));

        ReconciliationApplicationService service = service(
                new InMemoryReconciliationRepository(), imports,
                period -> List.of(new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1")),
                period -> List.of(), metrics, new StructuredAuditLogger());

        ReconciliationBatch batch = service.runReconciliation("2026-08");

        assertThat(batch.differenceAmountMinor()).isEqualTo(999L + 998L);
        assertThat(registry.get("reconciliation.difference_amount_minor")
                .tag("period", "2026-08")
                .counter().count()).isEqualTo(1997.0);
    }

    /** 无差异 ⇒ 不产出金额指标（避免 0 值噪声淹没真实告警）。 */
    @Test
    void differenceAmountMinorIsAbsentWhenNoDifferences() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
        seedImport(imports, "MOCK", "2026-08",
                List.of(legacyLine(1, "pay-1", 1000L, "SUCCEEDED")));

        ReconciliationApplicationService service = service(
                new InMemoryReconciliationRepository(), imports,
                period -> List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1")),
                period -> List.of(), metrics, new StructuredAuditLogger());

        ReconciliationBatch batch = service.runReconciliation("2026-08");

        assertThat(batch.differenceAmountMinor()).isZero();
        assertThat(registry.find("reconciliation.difference_amount_minor").counter()).isNull();
    }
}
