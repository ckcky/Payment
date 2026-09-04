package com.payment.settlement.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementStatus;
import com.payment.settlement.infra.InMemorySettlementAdjustmentRepository;
import com.payment.settlement.infra.InMemorySettlementRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结算业务指标（T072）：批次创建记 {@code settlement.batch_initiated}，模拟执行进入 UNKNOWN 记
 * {@code settlement.unknown}；负净额记 {@code settlement.negative_net}。
 */
class SettlementMetricsTest {

    @Test
    void recordsCreatedAndUnknownMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        InMemorySettlementRepository repository = new InMemorySettlementRepository();
        InMemorySettlementAdjustmentRepository adjustmentRepository =
                new InMemorySettlementAdjustmentRepository();
        MerchantClient merchantClient = id -> new MerchantView(id, "ACTIVE", true);
        ReconciliationClient reconciliationClient = period -> new ReconciliationSummary(period,
                List.of(
                        new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY"),
                        new SettlementFact("ref-2", "REFUND", 1000L, "CNY")),
                0);
        LedgerPostingGateway ledgerGateway = (idempotencyKey, batchId, netMinor, currencyCode) -> {
        };

        SettlementApplicationService service = new SettlementApplicationService(
                repository, merchantClient, reconciliationClient, adjustmentRepository,
                ledgerGateway, metrics, new StructuredAuditLogger());

        SettlementBatch batch = service.createBatch("1", "2026-08", "idem-1");

        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.UNKNOWN);
        assertThat(registry.get("settlement.batch_initiated").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("settlement.unknown").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsNegativeNetMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

        InMemorySettlementRepository repository = new InMemorySettlementRepository();
        InMemorySettlementAdjustmentRepository adjustmentRepository =
                new InMemorySettlementAdjustmentRepository();
        MerchantClient merchantClient = id -> new MerchantView(id, "ACTIVE", true);
        // 收入 1000 − 退款 2000 = −1000（负净额）
        ReconciliationClient reconciliationClient = period -> new ReconciliationSummary(period,
                List.of(
                        new SettlementFact("ref-1", "PAYMENT", 1000L, "CNY"),
                        new SettlementFact("ref-2", "REFUND", 2000L, "CNY")),
                0);
        LedgerPostingGateway ledgerGateway = (idempotencyKey, batchId, netMinor, currencyCode) -> {
        };

        SettlementApplicationService service = new SettlementApplicationService(
                repository, merchantClient, reconciliationClient, adjustmentRepository,
                ledgerGateway, metrics, new StructuredAuditLogger());

        service.createBatch("1", "2026-08", "idem-neg");

        assertThat(registry.get("settlement.negative_net").counter().count()).isEqualTo(1.0);
    }
}
