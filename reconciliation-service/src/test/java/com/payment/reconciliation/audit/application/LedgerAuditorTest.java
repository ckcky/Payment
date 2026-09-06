package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2 账账核对单测（spec 017 / T034）：借贷平衡、科目勾稽（含 F6 科目记错、SUSPENSE 勾稽）、跨账（F7）。
 */
class LedgerAuditorTest {

    private final LedgerAuditor auditor = new LedgerAuditor();

    private final List<CertificateFact> facts = List.of(
            new CertificateFact("PAYMENT", "PM-AUD-0001", null, 10000L, "CNY", "SUCCEEDED"),
            new CertificateFact("PAYMENT", "PM-AUD-0002", null, 25000L, "CNY", "SUCCEEDED"),
            new CertificateFact("REFUND", "RF-AUD-0001", null, 3000L, "CNY", "SUCCEEDED"));
    private final List<SettlementBatchFact> settlements = List.of(
            new SettlementBatchFact(99L, "SB-AUD-0001", "SUCCEEDED", 21750L, "CNY"));

    @Test
    void balancedLedgerProducesNoDifference() {
        List<LedgerPostingView> postings = List.of(
                postingPayment("LP-1", "PM-AUD-0001", 10000L),
                postingPayment("LP-2", "PM-AUD-0002", 25000L),
                postingRefund("LP-3", "RF-AUD-0001", 3000L),
                postingSettlement("LP-4", "99", 21750L));
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));

        List<AuditDifference> differences = auditor.audit(facts, settlements, postings, balance, 0L);
        assertThat(differences).isEmpty();
    }

    @Test
    void balanceBreakReportedWhenUnbalanced() {
        LedgerBalance balance = new LedgerBalance(false, Map.of("CNY", 500L));
        List<AuditDifference> differences = auditor.audit(facts, settlements, List.of(), balance, 0L);
        assertThat(differences).anyMatch(d -> d.getKind() == AuditDifferenceKind.BALANCE_BREAK);
    }

    @Test
    void f6_accountMisbookedStillBalancedIsCaughtByRecon() {
        // F6：手续费 250 被误记入 PLATFORM_FEE_REVENUE（本应进 MERCHANT_PAYABLE），借贷仍平衡
        List<LedgerPostingView> postings = List.of(
                postingPayment("LP-1", "PM-AUD-0001", 10000L),
                // PM-AUD-0002: 借 25000 / 贷 MERCHANT_PAYABLE 24750 + 贷 FEE 250
                new LedgerPostingView("LP-2", "ik-2", "PAYMENT", "PM-AUD-0002", "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(1L, "DEBIT", 25000L, "PAYMENT_CAPTURE", "PAYMENT", "PM-AUD-0002"),
                        new LedgerPostingView.LedgerEntryView(2L, "CREDIT", 24750L, "PAYMENT_CAPTURE", "PAYMENT", "PM-AUD-0002"),
                        new LedgerPostingView.LedgerEntryView(3L, "CREDIT", 250L, "FEE", "PAYMENT", "PM-AUD-0002"))),
                postingRefund("LP-3", "RF-AUD-0001", 3000L),
                postingSettlement("LP-4", "99", 21750L));
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));

        List<AuditDifference> differences = auditor.audit(facts, settlements, postings, balance, 0L);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.ACCOUNT_RECON_BREAK);
        assertThat(differences.get(0).getSourceId()).isEqualTo("MERCHANT_PAYABLE");
        // 勾稽差恰为 250
        assertThat(differences.get(0).getExpectedAmountMinor() - differences.get(0).getActualAmountMinor())
                .isEqualTo(250L);
    }

    @Test
    void suspenseReconBreakWhenUnclosedAmountDiffers() {
        List<LedgerPostingView> postings = List.of(
                // SUSPENSE 借方挂了 1000，但台账口径未收口挂账为 800
                new LedgerPostingView("LP-S", "ik-S", "ADJUSTMENT", "AD-1", "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(5L, "DEBIT", 1000L, "ADJUSTMENT", "ADJUSTMENT", "AD-1"),
                        new LedgerPostingView.LedgerEntryView(1L, "CREDIT", 1000L, "ADJUSTMENT", "ADJUSTMENT", "AD-1"))));
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));
        List<AuditDifference> differences = auditor.audit(facts, settlements, postings, balance, 800L);
        assertThat(differences).anyMatch(d -> d.getKind() == AuditDifferenceKind.ACCOUNT_RECON_BREAK
                && "SUSPENSE".equals(d.getSourceId()));
    }

    @Test
    void f7_crossLedgerMismatchReported() {
        List<LedgerPostingView> postings = List.of(
                postingSettlement("LP-4", "99", 21000L)); // 结算净额 21750 vs 账本 21000
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));
        List<AuditDifference> differences = auditor.audit(facts, settlements, postings, balance, 0L);
        assertThat(differences).anyMatch(d -> d.getKind() == AuditDifferenceKind.CROSS_LEDGER_MISMATCH
                && "SB-AUD-0001".equals(d.getSourceId()));
    }

    private LedgerPostingView postingPayment(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "ik-" + no, "PAYMENT", sourceId, "CNY", List.of(
                new LedgerPostingView.LedgerEntryView(1L, "DEBIT", amount, "PAYMENT_CAPTURE", "PAYMENT", sourceId),
                new LedgerPostingView.LedgerEntryView(2L, "CREDIT", amount, "PAYMENT_CAPTURE", "PAYMENT", sourceId)));
    }

    private LedgerPostingView postingRefund(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "ik-" + no, "REFUND", sourceId, "CNY", List.of(
                new LedgerPostingView.LedgerEntryView(2L, "DEBIT", amount, "REFUND", "REFUND", sourceId),
                new LedgerPostingView.LedgerEntryView(1L, "CREDIT", amount, "REFUND", "REFUND", sourceId)));
    }

    private LedgerPostingView postingSettlement(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "ik-" + no, "SETTLEMENT", sourceId, "CNY", List.of(
                new LedgerPostingView.LedgerEntryView(2L, "DEBIT", amount, "SETTLEMENT", "SETTLEMENT", sourceId),
                new LedgerPostingView.LedgerEntryView(4L, "CREDIT", amount, "SETTLEMENT", "SETTLEMENT", sourceId)));
    }
}
