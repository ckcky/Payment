package com.payment.reconciliation.audit.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditAdjustmentKind;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.audit.domain.AuditDifferenceStatus;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.audit.domain.AuditScope;
import com.payment.reconciliation.audit.infra.InMemoryAuditRepository;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * audit 侧人工收口（spec 032 §7.2 / T22，F7 关闭路径）+ 处置失败留痕（T22/§11 #9）：
 * 人工 resolve 直达 RESOLVED（备注必填，CROSS_LEDGER_MISMATCH 等不可自动转绿差异的关闭路径）；
 * 处置 RPC 失败 ⇒ 独立事务 FAILED 台账 + 差异 ADJUST_FAILED（不静默吞）。
 */
class AuditManualResolveTest {

    private final InMemoryAuditRepository auditRepository = new InMemoryAuditRepository();
    private final AuditApplicationService service = new AuditApplicationService(
            auditRepository, new InMemoryReconciliationRepository(), new FailAllFactsGateway(),
            new NoPostingLedgerGateway(),
            new CertificateAuditor(), new LedgerAuditor(), new RealAuditor(), new ReportAuditor(),
            new DispositionFailureRecorder(auditRepository),
            new NoopBusinessMetrics(), new StructuredAuditLogger(), false, false);

    /** 产出 1 条 CROSS_LEDGER_MISMATCH 差异的审计批次（F7 场景：recheck 无法自动转绿）。 */
    private AuditBatch seedBatchWithCrossLedgerDifference() {
        AuditBatch batch = auditRepository.insertBatch(
                new AuditBatch("2026-08", AuditScope.ALL, "test"));
        AuditDifference difference = AuditDifference.of(AuditDifferenceKind.CROSS_LEDGER_MISMATCH, "LEDGER",
                "LP-404", "LP-404", 1000L, 900L, "CNY", "跨账不平：对账批次净影响 1000 / 账本结算腿 900");
        difference = auditRepository.saveDifference(batch.getId(), difference);
        batch.finish(1, List.of(difference)); // HAS_DIFFERENCE：待处置批次（非 CLOSED）
        return batch;
    }

    @Test
    void manualResolveDrivesDifferenceToResolvedWithMandatoryNote() {
        AuditBatch batch = seedBatchWithCrossLedgerDifference();
        AuditDifference difference = batch.getDifferences().get(0);

        AuditDifference resolved = service.resolveDifference(batch.getBatchNo(), difference.getId(),
                "与渠道核实：渠道侧少记 100，次期账单补记", "ops-1", null);

        assertThat(resolved.getStatus()).isEqualTo(AuditDifferenceStatus.RESOLVED);
        assertThat(resolved.getResolutionNote()).contains("与渠道核实");
        assertThat(resolved.getResolvedBy()).isEqualTo("ops-1");
        assertThat(resolved.getResolvedAt()).isNotBlank();
        assertThat(auditRepository.findDifferenceById(difference.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditDifferenceStatus.RESOLVED);
    }

    @Test
    void resolveWithoutNoteIsRejected() {
        AuditBatch batch = seedBatchWithCrossLedgerDifference();
        AuditDifference difference = batch.getDifferences().get(0);

        assertThatThrownBy(() -> service.resolveDifference(batch.getBatchNo(), difference.getId(), " ", "ops-1", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("resolution note");
    }

    @Test
    void reResolvingResolvedDifferenceIsIdempotentNoOp() {
        AuditBatch batch = seedBatchWithCrossLedgerDifference();
        AuditDifference difference = batch.getDifferences().get(0);
        service.resolveDifference(batch.getBatchNo(), difference.getId(), "首次收口", "ops-1", null);

        AuditDifference again = service.resolveDifference(batch.getBatchNo(), difference.getId(),
                "重复收口尝试", "ops-2", null);

        assertThat(again.getStatus()).isEqualTo(AuditDifferenceStatus.RESOLVED);
        assertThat(again.getResolutionNote()).isEqualTo("首次收口");
        assertThat(again.getResolvedBy()).isEqualTo("ops-1");
    }

    @Test
    void dispositionFailureRecorderWritesFailedRowAndMarksDifferenceAdjustFailed() {
        AuditBatch batch = seedBatchWithCrossLedgerDifference();
        AuditDifference difference = batch.getDifferences().get(0);
        DispositionFailureRecorder recorder = new DispositionFailureRecorder(auditRepository);

        recorder.record(batch.getId(), difference.getId(), null, "ADJ-FAIL-1", AuditAdjustmentKind.SUSPEND,
                500L, "CNY", "ops-1", "自动挂账", "ledger unavailable");

        assertThat(auditRepository.findAdjustmentByNo("ADJ-FAIL-1"))
                .isPresent()
                .get()
                .satisfies(adjustment -> {
                    assertThat(adjustment.getStatus()).isEqualTo(AuditAdjustment.FAILED);
                    assertThat(adjustment.getReason()).contains("ledger unavailable");
                });
        assertThat(auditRepository.findDifferenceById(difference.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditDifferenceStatus.ADJUST_FAILED);
    }

    @Test
    void adjustFailedDifferenceIsVisibleAndNotCountedAsUnclosedGateBlockerSilently() {
        AuditBatch batch = seedBatchWithCrossLedgerDifference();
        AuditDifference difference = batch.getDifferences().get(0);
        new DispositionFailureRecorder(auditRepository).record(batch.getId(), difference.getId(), null,
                "ADJ-FAIL-2", AuditAdjustmentKind.SUSPEND, 500L, "CNY", "ops-1", "自动挂账", "boom");

        AuditDifference after = auditRepository.findDifferenceById(difference.getId()).orElseThrow();
        // ADJUST_FAILED 可见（状态可查询），不再静默回到 PENDING
        assertThat(after.getStatus()).isEqualTo(AuditDifferenceStatus.ADJUST_FAILED);
        assertThat(after.getStatus().name()).isNotEqualTo(AuditDifferenceStatus.PENDING.name());
    }

    /** 不记账网关：本测试类不触发处置动作，仅满足构造依赖。 */
    private static class NoPostingLedgerGateway implements AuditLedgerGateway {
        @Override
        public PostingResult postAdjustment(String adjustNo, String currency,
                                            com.payment.reconciliation.audit.domain.AdjustmentPolicy.AdjustPlan plan) {
            throw new IllegalStateException("not used in this test");
        }
    }

    /** 恒失败事实网关：本测试类不触发 runBatch，仅满足构造依赖。 */
    private static class FailAllFactsGateway implements AuditFactsGateway {
        @Override
        public List<CertificateFact> confirmedFacts(String period) {
            throw new BizException("DOWNSTREAM_ERROR", "facts unavailable");
        }

        @Override
        public List<LedgerPostingView> ledgerPostings() {
            return List.of();
        }

        @Override
        public LedgerBalance ledgerBalance() {
            return new LedgerBalance(true, java.util.Map.of());
        }

        @Override
        public List<SettlementBatchFact> settlementFacts(String period) {
            return List.of();
        }

        @Override
        public StatementLoad channelStatementLoad(String period) {
            return new StatementLoad(List.of(), null);
        }
    }
}
