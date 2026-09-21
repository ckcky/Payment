package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.legacyLine;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.seedImport;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 差异处理 → 批次生命周期（spec 006 T022 / ADR-0019）：首条处理推进 PROCESSING、
 * 未处理完关闭被拒、全部处理后关闭成功、CLOSED 为只读终态且重复关闭幂等。
 */
class ReconciliationLifecycleTest {

    private static final String PERIOD = "2026-08";

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ReconciliationApplicationService service;

    @BeforeEach
    void setUp() {
        seedImport(imports, "MOCK", PERIOD, List.of(
                legacyLine(1, "pay-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "channel-extra-1", 900L, "SUCCEEDED"),
                legacyLine(3, "channel-extra-2", 800L, "SUCCEEDED")));
        service = service(repository, imports,
                period -> List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED", "1")),
                period -> List.of(),
                new MicrometerBusinessMetrics(registry), new StructuredAuditLogger());
    }

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

    /**
     * 032 demo 实跑回归（T24b 同轮发现）：按 RD 单号收口时台账行**先于**批次视图更新，
     * 视图里该单号已是 RESOLVED——批次推进 MUST NOT 依赖「视图发生变化」判定，
     * 否则末笔/重放收口后批次滞留 HAS_DIFFERENCE，关批 409。
     */
    @Test
    void resolveByDiffNoAdvancesBatchAndAllowsClose() {
        ReconciliationBatch batch = batchWithTwoDifferences();
        List<String> diffNos = batch.getDifferences().stream().map(Difference::getDiffNo).toList();
        assertThat(diffNos).doesNotContainNull();

        service.resolveDifferenceByNo(diffNos.get(0), "checked", "ops", null);
        assertThat(repository.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(ReconciliationStatus.PROCESSING);

        service.resolveDifferenceByNo(diffNos.get(1), "checked", "ops", null);
        // 重放收口（同单号再来一次）不回退批次状态
        service.resolveDifferenceByNo(diffNos.get(1), "checked again", "ops", null);

        ReconciliationBatch closed = service.closeBatch(batch.getId(), "ops");
        assertThat(closed.getStatus()).isEqualTo(ReconciliationStatus.CLOSED);
    }
}
