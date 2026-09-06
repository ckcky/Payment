package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 账证核对单测（spec 017 / T028）：F1 平账 + F2~F5 故障 + 金额 / 币种 / 方向边界 + PENDING 不判差异。
 */
class CertificateAuditorTest {

    private final CertificateAuditor auditor = new CertificateAuditor();

    private final List<CertificateFact> baseFacts = List.of(
            new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"),
            new CertificateFact("PAYMENT", "PM-AUD-0002", "CH-AUD-0002", 25000L, "CNY", "SUCCEEDED"),
            new CertificateFact("REFUND", "RF-AUD-0001", "CH-RF-0001", 3000L, "CNY", "SUCCEEDED"));

    private final List<LedgerPostingView> basePostings = List.of(
            posting("LP-1", "PAYMENT", "PM-AUD-0001", 10000L),
            posting("LP-2", "PAYMENT", "PM-AUD-0002", 25000L),
            posting("LP-3", "REFUND", "RF-AUD-0001", 3000L));

    @Test
    void f1_balancedGroupProducesNoDifference() {
        List<AuditDifference> differences = auditor.audit(baseFacts, basePostings);
        assertThat(differences).isEmpty();
    }

    @Test
    void f2_missingPostingReported() {
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003", 8000L, "CNY", "SUCCEEDED"));
        List<AuditDifference> differences = auditor.audit(facts, List.of());
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.MISSING_POSTING);
        assertThat(differences.get(0).getExpectedAmountMinor()).isEqualTo(8000L);
        assertThat(differences.get(0).getActualAmountMinor()).isEqualTo(0L);
    }

    @Test
    void f3_orphanPostingReported() {
        List<LedgerPostingView> postings = List.of(
                posting("LP-G1", "PAYMENT", "PM-AUD-GHOST1", 5000L));
        List<AuditDifference> differences = auditor.audit(List.of(), postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.ORPHAN_POSTING);
        assertThat(differences.get(0).getSourceId()).isEqualTo("PM-AUD-GHOST1");
    }

    @Test
    void f4_amountMismatchReported() {
        List<LedgerPostingView> postings = List.of(
                posting("LP-1", "PAYMENT", "PM-AUD-0001", 9900L));
        List<AuditDifference> differences = auditor.audit(
                List.of(baseFacts.get(0)), postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.AMOUNT_MISMATCH);
    }

    @Test
    void f5_duplicatePostingReported() {
        List<LedgerPostingView> postings = List.of(
                posting("LP-2", "PAYMENT", "PM-AUD-0002", 25000L),
                posting("LP-2D", "PAYMENT", "PM-AUD-0002", 25000L));
        List<AuditDifference> differences = auditor.audit(
                List.of(baseFacts.get(1)), postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.DUPLICATE_POSTING);
    }

    @Test
    void currencyMismatchReported() {
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-USD", "ik", "PAYMENT", "PM-AUD-0001", "USD", List.of(
                        new LedgerPostingView.LedgerEntryView(1L, "DEBIT", 10000L, "PAYMENT_CAPTURE",
                                "PAYMENT", "PM-AUD-0001"),
                        new LedgerPostingView.LedgerEntryView(2L, "CREDIT", 10000L, "PAYMENT_CAPTURE",
                                "PAYMENT", "PM-AUD-0001"))));
        List<AuditDifference> differences = auditor.audit(List.of(baseFacts.get(0)), postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.CURRENCY_MISMATCH);
    }

    @Test
    void directionMismatchReported() {
        // 退款被记成了支付方向（客户资金借方为正，期望贷方为负）
        List<LedgerPostingView> postings = List.of(
                postingPaymentStyle("LP-3W", "RF-AUD-0001", 3000L));
        List<AuditDifference> differences = auditor.audit(
                List.of(baseFacts.get(2)), postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.DIRECTION_MISMATCH);
    }

    @Test
    void pendingFactNotJudgedAsDifference() {
        // FR-012：处理中的事实不判差异
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-PENDING", null, 8000L, "CNY", "PENDING"));
        List<AuditDifference> differences = auditor.audit(facts, List.of());
        assertThat(differences).isEmpty();
    }

    @Test
    void recheckBalancedAfterSuspenseAndTransfer() {
        // F2 挂账 + 转出后：处置分录（ADJUSTMENT）资金科目净影响补足缺口 → sourceBalanced
        CertificateFact missing = new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED");
        List<LedgerPostingView> adjustments = List.of(
                posting("LP-S1", "ADJUSTMENT", "AD-1", 8000L));
        assertThat(auditor.sourceBalanced(missing, List.of(), adjustments)).isTrue();
    }

    private LedgerPostingView postingPaymentStyle(String postingNo, String sourceId, long amount) {
        return new LedgerPostingView(postingNo, "ik-" + postingNo, "REFUND", sourceId, "CNY", List.of(
                new LedgerPostingView.LedgerEntryView(1L, "DEBIT", amount, "REFUND", "REFUND", sourceId),
                new LedgerPostingView.LedgerEntryView(2L, "CREDIT", amount, "REFUND", "REFUND", sourceId)));
    }

    /** 平账组 posting：支付 = 借 CUSTOMER_CASH(1) / 贷 MERCHANT_PAYABLE(2)；退款反向。 */
    private LedgerPostingView posting(String postingNo, String sourceType, String sourceId, long amount) {
        boolean refund = "REFUND".equals(sourceType);
        java.util.List<LedgerPostingView.LedgerEntryView> entries = new java.util.ArrayList<>();
        entries.add(new LedgerPostingView.LedgerEntryView(refund ? 2L : 1L, "DEBIT", amount,
                "PAYMENT_CAPTURE", sourceType, sourceId));
        entries.add(new LedgerPostingView.LedgerEntryView(refund ? 1L : 2L, "CREDIT", amount,
                refund ? "REFUND" : "PAYMENT_CAPTURE", sourceType, sourceId));
        return new LedgerPostingView(postingNo, "ik-" + postingNo, sourceType, sourceId, "CNY", entries);
    }
}
