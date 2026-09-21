package com.payment.reconciliation.application;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.audit.application.AuditLedgerGateway;
import com.payment.reconciliation.audit.application.DispositionFailureRecorder;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditAdjustmentKind;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.audit.infra.InMemoryAuditRepository;
import com.payment.reconciliation.domain.AutoDispositionPolicy;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationDifference;
import com.payment.reconciliation.domain.ReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动处置策略化（spec 032 G5 / H-032-6，T23/T24，AC-6）：策略门（超限/关闭/非 CHANNEL_ONLY
 * 不动）、成功留痕（ADJUSTMENT 事件 + audit_adjustments + 差异 SUSPENDED/dispositionRef）、
 * 失败可见（FAILED 台账 + 差异 ADJUST_FAILED，不静默吞）。
 */
class AutoDispositionServiceTest {

    private final InMemoryReconciliationRepository reconciliationRepository = new InMemoryReconciliationRepository();
    private final InMemoryAuditRepository auditRepository = new InMemoryAuditRepository();
    private final RecordingLedgerGateway ledgerGateway = new RecordingLedgerGateway();
    private final AutoDispositionService service = new AutoDispositionService(
            reconciliationRepository, ledgerGateway, auditRepository,
            new DispositionFailureRecorder(auditRepository),
            new NoopBusinessMetrics(), new StructuredAuditLogger(), true, 1000L);

    private ReconciliationBatch batch;

    @BeforeEach
    void seedBatch() {
        batch = new ReconciliationBatch("2026-08", "MOCK", "MOCK", 1L);
        batch.start();
        batch = reconciliationRepository.save(batch);
    }

    private ReconciliationDifference seedDifference(DifferenceType kind, String status,
                                                    Long actualAmountMinor, String currency) {
        ReconciliationDifference difference = ReconciliationDifference.of(batch.getId(), 1L, "2026-08",
                "M-1", "MOCK", kind, "PAYMENT", "ch-extra-1", null, actualAmountMinor, null, currency);
        if (status != null && !"PENDING".equals(status)) {
            difference = ReconciliationDifference.rehydrate(null, difference.getDiffNo(), batch.getId(), 1L,
                    "2026-08", "M-1", "MOCK", kind, "PAYMENT", "ch-extra-1",
                    null, actualAmountMinor, null, currency,
                    com.payment.reconciliation.domain.DifferenceStatus.valueOf(status), null, null, null, null);
        }
        return reconciliationRepository.insertDifference(difference);
    }

    @Test
    void eligibleChannelOnlyWithinLimitIsAutoSuspendedWithAuditTrail() {
        ReconciliationDifference difference = seedDifference(DifferenceType.CHANNEL_ONLY, "PENDING", 500L, "CNY");

        service.disposeBatch(batch.getId());

        ReconciliationDifference after = reconciliationRepository
                .findDifferenceByNo(difference.getDiffNo()).orElseThrow();
        assertThat(after.getStatus().name()).isEqualTo("SUSPENDED");
        assertThat(after.getDispositionRef()).isNotBlank();
        assertThat(ledgerGateway.postedAdjustNos).containsExactly(after.getDispositionRef());
        // audit_adjustments 留痕：diff_no 回溯 + POSTED
        List<AuditAdjustment> adjustments = auditRepository.findAdjustmentsByDifference(after.getId());
        assertThat(adjustments).isEmpty(); // recon 侧自动处置 batchId/differenceId 为空（按 diffNo 回溯）
        assertThat(auditRepository.findAdjustmentByNo(after.getDispositionRef()))
                .isPresent()
                .get()
                .satisfies(adjustment -> {
                    assertThat(adjustment.getStatus()).isEqualTo(AuditAdjustment.POSTED);
                    assertThat(adjustment.getDiffNo()).isEqualTo(difference.getDiffNo());
                    assertThat(adjustment.getKind()).isEqualTo(AuditAdjustmentKind.SUSPEND);
                });
    }

    @Test
    void overLimitDifferenceIsLeftForManualDisposition() {
        ReconciliationDifference difference = seedDifference(DifferenceType.CHANNEL_ONLY, "PENDING", 5001L, "CNY");

        service.disposeBatch(batch.getId());

        ReconciliationDifference after = reconciliationRepository
                .findDifferenceByNo(difference.getDiffNo()).orElseThrow();
        assertThat(after.getStatus().name()).isEqualTo("PENDING");
        assertThat(after.getDispositionRef()).isNull();
        assertThat(ledgerGateway.postedAdjustNos).isEmpty();
    }

    @Test
    void nonChannelOnlyDifferenceIsNeverAutoDisposed() {
        ReconciliationDifference difference = seedDifference(DifferenceType.PLATFORM_ONLY, "PENDING", 100L, "CNY");

        service.disposeBatch(batch.getId());

        assertThat(reconciliationRepository.findDifferenceByNo(difference.getDiffNo()).orElseThrow()
                .getStatus().name()).isEqualTo("PENDING");
        assertThat(ledgerGateway.postedAdjustNos).isEmpty();
    }

    @Test
    void alreadyResolvedDifferenceIsSkipped() {
        ReconciliationDifference difference = seedDifference(DifferenceType.CHANNEL_ONLY, "RESOLVED", 100L, "CNY");

        service.disposeBatch(batch.getId());

        assertThat(reconciliationRepository.findDifferenceByNo(difference.getDiffNo()).orElseThrow()
                .getStatus().name()).isEqualTo("RESOLVED");
        assertThat(ledgerGateway.postedAdjustNos).isEmpty();
    }

    @Test
    void postingFailureKeepsDifferenceVisibleAsAdjustFailedWithFailedLedgerRow() {
        ReconciliationDifference difference = seedDifference(DifferenceType.CHANNEL_ONLY, "PENDING", 500L, "CNY");
        ledgerGateway.failNext = true;

        service.disposeBatch(batch.getId());

        ReconciliationDifference after = reconciliationRepository
                .findDifferenceByNo(difference.getDiffNo()).orElseThrow();
        assertThat(after.getStatus().name()).isEqualTo("ADJUST_FAILED");
        assertThat(after.getDispositionRef()).isNotBlank();
        // 独立事务 FAILED 台账（§11 #9 不静默）：FAILED 行可见
        assertThat(auditRepository.findAdjustmentByNo(after.getDispositionRef()))
                .isPresent()
                .get()
                .satisfies(adjustment -> assertThat(adjustment.getStatus()).isEqualTo(AuditAdjustment.FAILED));
    }

    @Test
    void disabledPolicyPerformsNothingEvenForEligibleRows() {
        AutoDispositionService disabled = new AutoDispositionService(
                reconciliationRepository, ledgerGateway, auditRepository,
                new DispositionFailureRecorder(auditRepository),
                new NoopBusinessMetrics(), new StructuredAuditLogger(), false, 1000L);
        ReconciliationDifference difference = seedDifference(DifferenceType.CHANNEL_ONLY, "PENDING", 100L, "CNY");

        disabled.disposeBatch(batch.getId());

        assertThat(reconciliationRepository.findDifferenceByNo(difference.getDiffNo()).orElseThrow()
                .getStatus().name()).isEqualTo("PENDING");
        assertThat(ledgerGateway.postedAdjustNos).isEmpty();
    }

    @Test
    void policyGateIsPureAndConservative() {
        AutoDispositionPolicy policy = AutoDispositionPolicy.SMALL_CHANNEL_ONLY_SUSPEND;
        assertThat(policy.eligible(DifferenceType.CHANNEL_ONLY, "PENDING", 500L, "CNY", 1000L)).isTrue();
        assertThat(policy.eligible(DifferenceType.CHANNEL_ONLY, "PENDING", 1001L, "CNY", 1000L)).isFalse();
        assertThat(policy.eligible(DifferenceType.CHANNEL_ONLY, "PENDING", 500L, "USD", 1000L)).isFalse();
        assertThat(policy.eligible(DifferenceType.PLATFORM_ONLY, "PENDING", 500L, "CNY", 1000L)).isFalse();
        assertThat(policy.eligible(DifferenceType.CHANNEL_ONLY, "SUSPENDED", 500L, "CNY", 1000L)).isFalse();
    }

    /** 记录式记账网关：成功返回 postingNo；failNext ⇒ 抛异常模拟 ledger 不可用。 */
    private static class RecordingLedgerGateway implements AuditLedgerGateway {
        final List<String> postedAdjustNos = new java.util.ArrayList<>();
        boolean failNext = false;
        private final AtomicLong seq = new AtomicLong();

        @Override
        public PostingResult postAdjustment(String adjustNo, String currency, AdjustmentPolicy.AdjustPlan plan) {
            if (failNext) {
                throw new IllegalStateException("ledger unavailable");
            }
            postedAdjustNos.add(adjustNo);
            return new PostingResult("LP-" + seq.incrementAndGet(), String.valueOf(seq.get()));
        }
    }
}
