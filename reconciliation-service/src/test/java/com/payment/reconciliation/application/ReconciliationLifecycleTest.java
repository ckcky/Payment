package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 差异处理 → 批次生命周期（spec 006 T022 / ADR-0019）：首条处理推进 PROCESSING、
 * 未处理完关闭被拒、全部处理后关闭成功、CLOSED 为只读终态且重复关闭幂等。
 */
class ReconciliationLifecycleTest {

    private static final String PERIOD = "2026-08";

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReconciliationApplicationService service = new ReconciliationApplicationService(
            repository,
            () -> List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
            () -> List.of(),
            period -> new ChannelStatementLoadResult(
                    List.of(new ChannelStatement("pay-1", 1000L, "CNY", "SUCCEEDED"),
                            new ChannelStatement("channel-extra-1", 900L, "CNY", "SUCCEEDED"),
                            new ChannelStatement("channel-extra-2", 800L, "CNY", "SUCCEEDED")),
                    ChannelStatementSource.fixture("inline", 3, false)),
            new MicrometerBusinessMetrics(registry),
            new StructuredAuditLogger());

    /** 一个含 2 条渠道单边差异的批次（pay-1 与账单一致）。 */
    private ReconciliationBatch batchWithTwoDifferences() {
        ReconciliationBatch batch = service.runReconciliation(PERIOD);
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.HAS_DIFFERENCE);
        assertThat(batch.getDifferences()).hasSize(2);
        return batch;
    }

    @Test
    void firstResolveMovesBatchToProcessing() {
        ReconciliationBatch batch = batchWithTwoDifferences();

        service.resolveDifference(batch.getId(), "channel-extra-1", "checked with channel", "ops", null);

        assertThat(repository.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(ReconciliationStatus.PROCESSING);
    }

    @Test
    void closeIsRejectedWhileDifferencesRemain() {
        ReconciliationBatch batch = batchWithTwoDifferences();
        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);

        assertThatThrownBy(() -> service.closeBatch(batch.getId(), "ops"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("unresolved differences");
        assertThat(repository.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(ReconciliationStatus.PROCESSING);
    }

    @Test
    void closeSucceedsAfterAllDifferencesResolved() {
        ReconciliationBatch batch = batchWithTwoDifferences();
        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);
        service.resolveDifference(batch.getId(), "channel-extra-2", "checked", "ops", null);

        ReconciliationBatch closed = service.closeBatch(batch.getId(), "ops");

        assertThat(closed.getStatus()).isEqualTo(ReconciliationStatus.CLOSED);
        assertThat(closed.getClosedBy()).isEqualTo("ops");
        assertThat(closed.getClosedAt()).isNotBlank();
    }

    @Test
    void resolvingDifferenceOnClosedBatchIsRejected() {
        ReconciliationBatch batch = batchWithTwoDifferences();
        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);
        service.resolveDifference(batch.getId(), "channel-extra-2", "checked", "ops", null);
        service.closeBatch(batch.getId(), "ops");

        assertThatThrownBy(() -> service.resolveDifference(batch.getId(), "channel-extra-1", "again", "ops", null))
                .isInstanceOf(BizException.class);
        assertThat(repository.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(ReconciliationStatus.CLOSED);
    }

    @Test
    void closingClosedBatchIsIdempotent() {
        ReconciliationBatch batch = batchWithTwoDifferences();
        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);
        service.resolveDifference(batch.getId(), "channel-extra-2", "checked", "ops", null);
        ReconciliationBatch first = service.closeBatch(batch.getId(), "ops");
        String closedAt = first.getClosedAt();

        ReconciliationBatch again = service.closeBatch(batch.getId(), "ops-2");

        assertThat(again.getStatus()).isEqualTo(ReconciliationStatus.CLOSED);
        assertThat(again.getClosedBy()).isEqualTo("ops");
        assertThat(again.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    void lifecycleMetricsAndAuditAreRecorded() {
        ReconciliationBatch batch = batchWithTwoDifferences();
        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);
        service.resolveDifference(batch.getId(), "channel-extra-2", "checked", "ops", null);
        service.closeBatch(batch.getId(), "ops");

        assertThat(registry.find("reconciliation.difference_resolved").counter()).isNotNull();
        assertThat(registry.find("reconciliation.batch_closed").counter())
                .isNotNull()
                .extracting(c -> c.count())
                .isEqualTo(1.0);
    }
}
