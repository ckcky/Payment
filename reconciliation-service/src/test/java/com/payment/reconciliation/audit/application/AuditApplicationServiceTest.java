package com.payment.reconciliation.audit.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.audit.domain.AuditDifferenceStatus;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.audit.infra.InMemoryAuditRepository;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计闭环场景测试（spec 017 / T029 + T059，内存仓储 + fake 网关，不起 Spring）：
 * F1 平账 / F2 漏记账挂账→转出→recheck→关批 全链路 + 幂等 + 门禁 + 试算平衡。
 * 覆盖 SC-004 / SC-006 / SC-008 / SC-009 / SC-010 / SC-012 / SC-015 / SC-016 / SC-018。
 *
 * <p>spec 031 口径：fixture posting 携带科目码（accountCode）腿——
 * PAYMENT=Dr CHANNEL_RECEIVABLE/Cr MERCHANT_PAYABLE、REFUND 反向、
 * SETTLEMENT=Dr MERCHANT_PAYABLE/Cr SETTLEMENT_PAYABLE（sourceId=batchNo，M1）；
 * fake 记账网关按 AdjustPlan「Dr to / Cr from」展开（幂等键 ADJUSTMENT:{adjustNo} 由账本派生，
 * 落账 sourceType=RECONCILIATION）。</p>
 */
class AuditApplicationServiceTest {

    // ---- fakes ----

    /** fake 事实网关：postings 列表与记账网关共享（调账分录对 recheck 可见）。 */
    private static class FakeAuditFactsGateway implements AuditFactsGateway {
        List<CertificateFact> facts = new ArrayList<>();
        List<LedgerPostingView> postings = new ArrayList<>();
        List<ChannelStatement> statements = new ArrayList<>();
        List<SettlementBatchFact> settlements = new ArrayList<>();
        boolean failFacts = false;

        @Override
        public List<CertificateFact> confirmedFacts(String period) {
            if (failFacts) {
                throw new IllegalStateException("payment facts unavailable");
            }
            List<CertificateFact> merged = new ArrayList<>(facts);
            for (SettlementBatchFact settlement : settlements) {
                // 031/M1：SETTLEMENT 账证事实 sourceId = batchNo（账本 posting 同键）
                merged.add(new CertificateFact("SETTLEMENT", settlement.batchNo(),
                        settlement.batchNo(), settlement.netMinor(), settlement.currency(), settlement.status()));
            }
            return merged;
        }

        @Override
        public List<LedgerPostingView> ledgerPostings() {
            return postings;
        }

        @Override
        public LedgerBalance ledgerBalance() {
            Map<String, Long> diff = new LinkedHashMap<>();
            for (LedgerPostingView posting : postings) {
                long signed = posting.entries().stream()
                        .mapToLong(e -> "DEBIT".equals(e.direction()) ? e.amountMinor() : -e.amountMinor())
                        .sum();
                diff.merge(posting.currency(), signed, Long::sum);
            }
            boolean balanced = diff.values().stream().allMatch(d -> d == 0L);
            return new LedgerBalance(balanced, diff);
        }

        @Override
        public List<SettlementBatchFact> settlementFacts(String period) {
            return settlements;
        }

        @Override
        public List<ChannelStatement> channelStatements(String period) {
            return statements;
        }
    }

    /** fake 记账网关：按 ADJUSTMENT:{adjustNo} 幂等回放 + 计划「Dr to / Cr from」落进共享 postings。 */
    private static class FakeAuditLedgerGateway implements AuditLedgerGateway {
        private final FakeAuditFactsGateway factsGateway;
        private final Map<String, PostingResult> byAdjustNo = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        FakeAuditLedgerGateway(FakeAuditFactsGateway factsGateway) {
            this.factsGateway = factsGateway;
        }

        @Override
        public PostingResult postAdjustment(String adjustNo, String currency, AdjustmentPolicy.AdjustPlan plan) {
            String idempotencyKey = "ADJUSTMENT:" + adjustNo;
            if (byAdjustNo.containsKey(idempotencyKey)) {
                return byAdjustNo.get(idempotencyKey); // SC-013：同幂等键回放首次结果
            }
            if (plan.fromAccountCode() == null || plan.toAccountCode() == null) {
                throw new UnsupportedOperationException(
                        "REVERSE/CORRECT 红冲镜像不在本场景测试范围（见 ledger AdjustmentPostingTest）");
            }
            String postingNo = "LP-AUD-" + seq.incrementAndGet();
            List<LedgerPostingView.LedgerEntryView> views = List.of(
                    entry(plan.toAccountCode(), "DEBIT", plan.amountMinor()),
                    entry(plan.fromAccountCode(), "CREDIT", plan.amountMinor()));
            // 031：source_type=RECONCILIATION、source_id=adjustNo、eventType=ADJUSTMENT
            factsGateway.postings.add(new LedgerPostingView(postingNo, "ADJUSTMENT", idempotencyKey,
                    "RECONCILIATION", adjustNo, currency, views));
            PostingResult result = new PostingResult(postingNo, postingNo);
            byAdjustNo.put(idempotencyKey, result);
            return result;
        }
    }

    private final FakeAuditFactsGateway factsGateway = new FakeAuditFactsGateway();
    private final FakeAuditLedgerGateway ledgerGateway = new FakeAuditLedgerGateway(factsGateway);
    private final AuditRepository auditRepository = new InMemoryAuditRepository();
    private final InMemoryReconciliationRepository reconciliationRepository = new InMemoryReconciliationRepository();

    private AuditApplicationService service() {
        return new AuditApplicationService(auditRepository, reconciliationRepository, factsGateway,
                ledgerGateway, new CertificateAuditor(), new LedgerAuditor(), new RealAuditor(),
                new ReportAuditor(), new NoopBusinessMetrics(), new StructuredAuditLogger(), false, false);
    }

    /** 平账组 F1：3 笔业务事实 + 对应分录 + 1 结算批次（fee=0 口径）。 */
    private void loadBalancedGroup() {
        factsGateway.facts.addAll(List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"),
                new CertificateFact("PAYMENT", "PM-AUD-0002", "CH-AUD-0002", 25000L, "CNY", "SUCCEEDED"),
                new CertificateFact("REFUND", "RF-AUD-0001", "CH-RF-0001", 3000L, "CNY", "SUCCEEDED")));
        factsGateway.settlements.add(new SettlementBatchFact(99L, "SB-AUD-0001", "SUCCEEDED", 21750L, "CNY"));
        factsGateway.postings.addAll(List.of(
                paymentPosting("LP-1", "PM-AUD-0001", 10000L),
                paymentPosting("LP-2", "PM-AUD-0002", 25000L),
                refundPosting("LP-3", "RF-AUD-0001", 3000L),
                settlementPosting("LP-4", "SB-AUD-0001", 21750L)));
        factsGateway.statements.addAll(List.of(
                new ChannelStatement("CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"),
                new ChannelStatement("CH-AUD-0002", 25000L, "CNY", "SUCCEEDED"),
                new ChannelStatement("CH-RF-0001", 3000L, "CNY", "SUCCEEDED")));
    }

    @Test
    void sc004_f1_balancedGroupRunsCleanWithFullCoverage() {
        loadBalancedGroup();
        AuditBatch batch = service().runBatch("2026-08-31", "ALL", "test");

        assertThat(batch.getStatus().name()).isEqualTo("BALANCED");
        assertThat(batch.getDifferences()).isEmpty();
        assertThat(batch.getCheckedCount()).isEqualTo(4); // 3 业务事实 + 1 结算批次
    }

    @Test
    void sc006_idempotentRetriggerReturnsFirstBatch() {
        loadBalancedGroup();
        AuditApplicationService svc = service();
        AuditBatch first = svc.runBatch("2026-08-31", "ALL", "test");
        AuditBatch second = svc.runBatch("2026-08-31", "ALL", "test");
        assertThat(second.getBatchNo()).isEqualTo(first.getBatchNo());
    }

    @Test
    void sc008_f2_suspendPostsBalancedEntryAndKeepsBusinessUntouched() {
        // F2 漏记账：业务有 PM-AUD-0003 8000，账本无
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));

        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");
        assertThat(batch.getStatus().name()).isEqualTo("HAS_DIFFERENCE");
        AuditDifference missing = batch.getDifferences().stream()
                .filter(d -> d.getKind() == AuditDifferenceKind.MISSING_POSTING)
                .findFirst().orElseThrow();
        assertThat(missing.getExpectedAmountMinor()).isEqualTo(8000L);
        assertThat(missing.getActualAmountMinor()).isEqualTo(0L);

        AuditAdjustment suspension = svc.suspend(batch.getBatchNo(), missing.getId(), "demo-op", "挂账演示");
        assertThat(suspension.getPostingNo()).isNotBlank();
        assertThat(suspension.getKind().name()).isEqualTo("SUSPEND");

        AuditDifference after = svc.listDifferences(batch.getBatchNo()).stream()
                .filter(d -> d.getKind() == AuditDifferenceKind.MISSING_POSTING)
                .findFirst().orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuditDifferenceStatus.SUSPENDED);
        // SC-016：SUSPENSE 余额 == 未收口挂账净额（欠记挂账 = Dr BANK_CASH / Cr SUSPENSE）
        assertThat(svc.suspenseBalanceMinor()).isEqualTo(8000L);
    }

    @Test
    void sc009_sc010_transferThenRecheckVerifiesAndSuspenseDropsToZero() {
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));
        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");
        AuditDifference missing = batch.getDifferences().stream()
                .filter(d -> d.getKind() == AuditDifferenceKind.MISSING_POSTING)
                .findFirst().orElseThrow();

        svc.suspend(batch.getBatchNo(), missing.getId(), "demo-op", "挂账演示");
        AuditAdjustment transfer = svc.adjust(batch.getBatchNo(), missing.getId(), "TRANSFER",
                8000L, "MERCHANT_PAYABLE", "demo-op", "demo-rev", "查清归属，转出挂账");

        assertThat(transfer.getPostingNo()).isNotBlank();
        // SC-009：SUSPENSE 归零（TRANSFER = Dr SUSPENSE / Cr MERCHANT_PAYABLE）
        assertThat(svc.suspenseBalanceMinor()).isEqualTo(0L);
        // SC-010：调账后自动 recheck → VERIFIED（资金腿净影响 = +8000 = 事实金额）
        AuditDifference after = svc.listDifferences(batch.getBatchNo()).stream()
                .filter(d -> d.getKind() == AuditDifferenceKind.MISSING_POSTING)
                .findFirst().orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuditDifferenceStatus.VERIFIED);
    }

    @Test
    void sc015_closeGateBlocksUntilAllDifferencesVerified() {
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));
        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");

        // 未收口 → 400
        assertThatThrownBy(() -> svc.close(batch.getBatchNo(), "demo-op"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("unclosed differences");

        // 全部处置 + recheck
        for (AuditDifference difference : new ArrayList<>(svc.listDifferences(batch.getBatchNo()))) {
            svc.suspend(batch.getBatchNo(), difference.getId(), "demo-op", "挂账");
            svc.adjust(batch.getBatchNo(), difference.getId(), "TRANSFER", difference.differenceAmountMinor(),
                    "MERCHANT_PAYABLE", "demo-op", "demo-rev", "转出挂账");
        }
        AuditBatch closed = svc.close(batch.getBatchNo(), "demo-op");
        assertThat(closed.getStatus().name()).isEqualTo("CLOSED");
    }

    @Test
    void sc018_trialBalanceHoldsAfterAnyDispositionSequence() {
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));
        factsGateway.postings.add(paymentPosting("LP-G1", "PM-AUD-GHOST1", 5000L)); // F3 孤儿
        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");
        assertThat(batch.getDifferences()).hasSize(2);

        for (AuditDifference difference : new ArrayList<>(svc.listDifferences(batch.getBatchNo()))) {
            svc.suspend(batch.getBatchNo(), difference.getId(), "demo-op", "挂账");
            svc.adjust(batch.getBatchNo(), difference.getId(), "TRANSFER", difference.differenceAmountMinor(),
                    "MERCHANT_PAYABLE", "demo-op", "demo-rev", "转出挂账");
        }
        assertThat(svc.trialBalance().balanced()).isTrue(); // Σ(借−贷)=0
    }

    @Test
    void sc012_overAmountAdjustRejectedWithoutAnyPosting() {
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));
        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");
        AuditDifference missing = batch.getDifferences().stream()
                .filter(d -> d.getKind() == AuditDifferenceKind.MISSING_POSTING)
                .findFirst().orElseThrow();

        int postingsBefore = factsGateway.postings.size();
        assertThatThrownBy(() -> svc.adjust(batch.getBatchNo(), missing.getId(), "SUPPLEMENT",
                9000L, null, "demo-op", "demo-rev", "超额调账"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ADJUST_AMOUNT_EXCEEDED");
        assertThat(factsGateway.postings.size()).isEqualTo(postingsBefore); // 不产生任何分录
    }

    @Test
    void settlementGateBlocksPendingBlockerAndAllowsSuspended() {
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));
        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");

        // BLOCKER 且 PENDING → BLOCK
        AuditApplicationService.SettlementGateResponse blocked = svc.settlementGate("2026-08-31");
        assertThat(blocked.decision()).isEqualTo("BLOCK");

        // 挂账后 → 放行留痕（plan §6.1 分级门禁）
        for (AuditDifference difference : new ArrayList<>(svc.listDifferences(batch.getBatchNo()))) {
            svc.suspend(batch.getBatchNo(), difference.getId(), "demo-op", "挂账");
        }
        AuditApplicationService.SettlementGateResponse allowed = svc.settlementGate("2026-08-31");
        assertThat(allowed.decision()).isEqualTo("ALLOW");
    }

    @Test
    void nfr008_factSourceFailureFailsBatchWithoutSilentClean() {
        loadBalancedGroup();
        factsGateway.failFacts = true;
        assertThatThrownBy(() -> service().runBatch("2026-08-31", "CERTIFICATE", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void writeOffRejectedWhenDisabled() {
        loadBalancedGroup();
        factsGateway.facts.add(new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED"));
        AuditApplicationService svc = service();
        AuditBatch batch = svc.runBatch("2026-08-31", "CERTIFICATE", "test");
        AuditDifference missing = batch.getDifferences().get(0);
        assertThatThrownBy(() -> svc.adjust(batch.getBatchNo(), missing.getId(), "WRITE_OFF",
                100L, null, "demo-op", "demo-rev", "核销"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("WRITE_OFF disabled");
    }

    // ---- helpers ----

    private static LedgerPostingView.LedgerEntryView entry(String accountCode, String direction, long amount) {
        return new LedgerPostingView.LedgerEntryView(1L, accountCode, "PLATFORM", "LEDGER", direction, amount);
    }

    private LedgerPostingView paymentPosting(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "PAYMENT_CAPTURE", "PAYMENT_CAPTURE:" + sourceId, "PAYMENT", sourceId,
                "CNY", List.of(
                entry("CHANNEL_RECEIVABLE", "DEBIT", amount),
                entry("MERCHANT_PAYABLE", "CREDIT", amount)));
    }

    private LedgerPostingView refundPosting(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "REFUND", "REFUND:" + sourceId, "REFUND", sourceId,
                "CNY", List.of(
                entry("MERCHANT_PAYABLE", "DEBIT", amount),
                entry("CHANNEL_RECEIVABLE", "CREDIT", amount)));
    }

    /** MERCHANT_SETTLEMENT：sourceId=batchNo（M1），Dr MP / Cr SP，带符号 MP 腿 = 批次净额。 */
    private LedgerPostingView settlementPosting(String no, String batchNo, long amount) {
        return new LedgerPostingView(no, "MERCHANT_SETTLEMENT", "MERCHANT_SETTLEMENT:" + batchNo,
                "SETTLEMENT", batchNo, "CNY", List.of(
                entry("MERCHANT_PAYABLE", "DEBIT", amount),
                entry("SETTLEMENT_PAYABLE", "CREDIT", amount)));
    }
}
