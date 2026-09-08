package com.payment.reconciliation.api;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.application.ChannelStatementLoadResult;
import com.payment.reconciliation.application.ChannelStatementLoader;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次响应口径（spec 006 T039 / T010、T041 / INV-12）：
 * {@code unresolvedDifferenceCount} 与 {@code settlementSummary} 必须同源同值，
 * 否则前端会同时显示「还有 2 条未处理」和「结算可全额下发」两种互相矛盾的结论。
 */
class ReconciliationBatchResponseTest {

    private static final String PERIOD = "2026-08";

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final ReconciliationApplicationService service = new ReconciliationApplicationService(
            repository,
            (PaymentFactsClient) () -> List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
            (RefundFactsClient) List::of,
            (ChannelStatementLoader) period -> new ChannelStatementLoadResult(
                    List.of(new ChannelStatement("pay-1", 1000L, "CNY", "SUCCEEDED"),
                            new ChannelStatement("channel-extra-1", 900L, "CNY", "SUCCEEDED"),
                            new ChannelStatement("channel-extra-2", 800L, "CNY", "SUCCEEDED")),
                    ChannelStatementSource.fixture("inline", 3, false)),
            new MicrometerBusinessMetrics(new SimpleMeterRegistry()),
            new StructuredAuditLogger());

    @Test
    void unresolvedCountMatchesBatchStateBeforeAnyResolve() {
        ReconciliationBatch batch = service.runReconciliation(PERIOD);

        ReconciliationBatchResponse response = ReconciliationBatchResponse.from(batch);

        assertThat(response.differenceCount()).isEqualTo(2);
        assertThat(response.unresolvedDifferenceCount()).isEqualTo(2);
        assertThat(response.unresolvedDifferenceCount()).isEqualTo((int) batch.unresolvedDifferenceCount());
    }

    @Test
    void unresolvedCountMatchesSettlementSummaryAfterPartialResolve() {
        ReconciliationBatch batch = service.runReconciliation(PERIOD);
        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);

        ReconciliationBatch stored = repository.findById(batch.getId()).orElseThrow();
        ReconciliationBatchResponse response = ReconciliationBatchResponse.from(stored);
        ReconciliationSettlementSummaryResponse summary = service.settlementSummary(PERIOD);

        assertThat(stored.getStatus()).isEqualTo(ReconciliationStatus.PROCESSING);
        assertThat(response.unresolvedDifferenceCount()).isEqualTo(1);
        assertThat(summary.unresolvedDifferenceCount()).isEqualTo(1);
        assertThat(response.unresolvedDifferenceCount()).isEqualTo(summary.unresolvedDifferenceCount());
    }

    @Test
    void unresolvedCountIsZeroWhenAllResolved() {
        ReconciliationBatch batch = service.runReconciliation(PERIOD);
        for (Difference difference : List.copyOf(batch.getDifferences())) {
            service.resolveDifference(batch.getId(), difference.getReference(), "checked", "ops", null);
        }

        ReconciliationBatch stored = repository.findById(batch.getId()).orElseThrow();

        assertThat(ReconciliationBatchResponse.from(stored).unresolvedDifferenceCount()).isZero();
        assertThat(service.settlementSummary(PERIOD).unresolvedDifferenceCount()).isZero();
    }

    @Test
    void responseCarriesStatementSourceAndClosureTrace() {
        ReconciliationBatch batch = service.runReconciliation(PERIOD);
        for (Difference difference : List.copyOf(batch.getDifferences())) {
            service.resolveDifference(batch.getId(), difference.getReference(), "checked", "ops", null);
        }
        ReconciliationBatch closed = service.closeBatch(batch.getId(), "ops-closer");

        ReconciliationBatchResponse response = ReconciliationBatchResponse.from(closed);

        assertThat(response.statementSource()).contains("FIXTURE");
        assertThat(response.closedBy()).isEqualTo("ops-closer");
        assertThat(response.closedAt()).isNotBlank();
    }
}
