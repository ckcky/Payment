package com.payment.reconciliation.application;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.testsupport.ReconciliationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.legacyLine;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.seedImport;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账编排测试（US3 + spec 032 §10.1）：以导入账单为外部事实来源，一次执行产出正确
 * 状态与匹配/差异计数；同导入重复执行幂等返回同一批次；无可用导入 ⇒ 400 STATEMENT_UNAVAILABLE。
 */
class ReconciliationApplicationServiceTest {

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();

    private final PaymentFactsClient payments = period -> List.of(
            new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1"),
            new PlatformFact("mock-ref-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED", "1"));

    private final RefundFactsClient refunds = period -> List.of(
            new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED", "1"));

    private ReconciliationApplicationService service() {
        seedImport(imports, "MOCK", "2026-08", List.of(
                legacyLine(1, "mock-ref-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "mock-ref-2", 2000L, "SUCCEEDED"),
                legacyLine(3, "refund-1", 500L, "SUCCEEDED"),
                legacyLine(4, "channel-extra-1", 999L, "SUCCEEDED")));
        return ReconciliationTestSupport.service(repository, imports, payments, refunds,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
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
        seedImport(imports, "MOCK", "2026-09",
                List.of(legacyLine(1, "ref-1", 1000L, "SUCCEEDED")));
        ReconciliationApplicationService service = ReconciliationTestSupport.service(
                repository, imports,
                period -> List.of(new PlatformFact("ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1")),
                period -> List.of(),
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        ReconciliationBatch batch = service.runReconciliation("2026-09");

        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(batch.getMatches()).hasSize(1);
        assertThat(batch.getDifferences()).isEmpty();
    }

    @Test
    void runWithoutNormalizedImportIsRejectedWithStatementUnavailable() {
        ReconciliationApplicationService service = ReconciliationTestSupport.service(
                repository, imports, payments, refunds,
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runReconciliation("2026-07"))
                .isInstanceOfSatisfying(com.payment.common.core.error.BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode())
                                .isEqualTo(com.payment.common.core.error.ErrorCodes.STATEMENT_UNAVAILABLE));
    }

    @Test
    void closeBatchWithUnresolvedDifferencesIsRejected() {
        ReconciliationApplicationService service = service();
        ReconciliationBatch batch = service.runReconciliation("2026-08");

        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.HAS_DIFFERENCE);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.payment.common.core.error.BizException.class,
                () -> service.closeBatch(batch.getId(), "ops-1"));
    }

    @Test
    void resolveDifferenceThenCloseReachesClosed() {
        ReconciliationApplicationService service = service();
        ReconciliationBatch batch = service.runReconciliation("2026-08");

        service.resolveDifference(batch.getId(), "channel-extra-1", "渠道多笔，确认无误", "ops-1", null);
        ReconciliationBatch closed = service.closeBatch(batch.getId(), "ops-1");

        assertThat(closed.getStatus()).isEqualTo(ReconciliationStatus.CLOSED);
        assertThat(closed.getClosedBy()).isEqualTo("ops-1");
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    void statementSourceIsRecordedOnBatch() {
        ReconciliationApplicationService service = service();
        ReconciliationBatch batch = service.runReconciliation("2026-08");

        assertThat(batch.getStatementSource()).isNotNull();
        assertThat(batch.getStatementSource().sourceType()).isEqualTo("IMPORT");
        assertThat(batch.getStatementSource().fallbackUsed()).isFalse();
    }

    /**
     * 按周期区分（spec 006 T012 / FR-001）：不同周期导入不同账单 ⇒ 产出不同差异集合与不同批次
     * （周期幂等键 (period, channel, importId) 互不相交）。
     */
    @Test
    void differentPeriodsProduceDifferentDifferenceSets() {
        InMemoryReconciliationRepository repo = new InMemoryReconciliationRepository();
        InMemoryStatementImportRepository importsRepo = new InMemoryStatementImportRepository();
        seedImport(importsRepo, "MOCK", "2026-08", List.of(
                legacyLine(1, "mock-ref-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "ch-aug-extra", 700L, "SUCCEEDED")));
        seedImport(importsRepo, "MOCK", "2026-09", List.of(
                legacyLine(1, "mock-ref-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "ch-sep-extra", 1500L, "SUCCEEDED")));
        ReconciliationApplicationService service = ReconciliationTestSupport.service(
                repo, importsRepo,
                period -> List.of(new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1")),
                period -> List.of(),
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        ReconciliationBatch aug = service.runReconciliation("2026-08");
        ReconciliationBatch sep = service.runReconciliation("2026-09");

        assertThat(aug.getDifferences()).extracting("reference").containsExactly("ch-aug-extra");
        assertThat(sep.getDifferences()).extracting("reference").containsExactly("ch-sep-extra");
        assertThat(aug.getId()).isNotEqualTo(sep.getId());
        assertThat(aug.getStatementSource().locator()).isNotEqualTo(sep.getStatementSource().locator());
    }

    /** 显式指定 importNo（更正账单重对入口）：以该导入为准执行核对。 */
    @Test
    void runWithExplicitImportUsesThatImport() {
        InMemoryReconciliationRepository repo = new InMemoryReconciliationRepository();
        InMemoryStatementImportRepository importsRepo = new InMemoryStatementImportRepository();
        StatementImport correction = seedImport(importsRepo, "MOCK", "2026-08", List.of(
                legacyLine(1, "mock-ref-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "ch-corrected-extra", 300L, "SUCCEEDED")));
        ReconciliationApplicationService service = ReconciliationTestSupport.service(
                repo, importsRepo,
                period -> List.of(new PlatformFact("mock-ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1")),
                period -> List.of(),
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        ReconciliationBatch batch = service.runReconciliation("2026-08", "MOCK", correction.getImportNo());

        assertThat(batch.getImportId()).isEqualTo(correction.getId());
        assertThat(batch.getDifferences()).extracting("reference").containsExactly("ch-corrected-extra");
    }
}
