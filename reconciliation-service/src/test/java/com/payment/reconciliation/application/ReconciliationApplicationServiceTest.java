package com.payment.reconciliation.application;

import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账编排测试（US3）：一次执行产出正确状态与匹配/差异计数；同周期重复执行幂等返回同一批次。
 */
class ReconciliationApplicationServiceTest {

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final InMemoryIdempotencyRegistry registry = new InMemoryIdempotencyRegistry();

    private final PaymentFactsClient payments = () -> List.of(
            new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"),
            new PlatformFact("mock-ref-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED"));

    private final RefundFactsClient refunds = () -> List.of(
            new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED"));

    private final ChannelStatementLoader loader = period -> List.of(
            new ChannelStatement("mock-ref-1", 1000L, "CNY", "SUCCEEDED"),
            new ChannelStatement("mock-ref-2", 2000L, "CNY", "SUCCEEDED"),
            new ChannelStatement("refund-1", 500L, "CNY", "SUCCEEDED"),
            new ChannelStatement("channel-extra-1", 999L, "CNY", "SUCCEEDED"));

    private ReconciliationApplicationService service() {
        return new ReconciliationApplicationService(repository, payments, refunds, registry, loader);
    }

    @Test
    void runReconciliationProducesBatchWithMatchesAndChannelOnlyDifference() {
        ReconciliationBatch batch = service().runReconciliation("2026-08");

        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.HAS_DIFFERENCE);
        assertThat(batch.getMatches()).hasSize(3);
        assertThat(batch.getDifferences()).hasSize(1);
        assertThat(batch.getDifferences().get(0).getType()).isEqualTo(DifferenceType.CHANNEL_ONLY);
        assertThat(batch.getDifferences().get(0).getReference()).isEqualTo("channel-extra-1");
    }

    @Test
    void duplicateRunWithSamePeriodReturnsSameBatchId() {
        ReconciliationApplicationService service = service();
        ReconciliationBatch first = service.runReconciliation("2026-08");
        ReconciliationBatch second = service.runReconciliation("2026-08");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void consistentBatchWhenNoDifferences() {
        ReconciliationApplicationService service = new ReconciliationApplicationService(
                repository,
                () -> List.of(new PlatformFact("ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                () -> List.of(),
                registry,
                period -> List.of(new ChannelStatement("ref-1", 1000L, "CNY", "SUCCEEDED")));

        ReconciliationBatch batch = service.runReconciliation("2026-09");

        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(batch.getMatches()).hasSize(1);
        assertThat(batch.getDifferences()).isEmpty();
    }
}
