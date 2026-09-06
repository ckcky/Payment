package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.domain.ChannelStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3 账实核对单测（spec 017 / T038）：F8 长款 / 金额不符；正常组不误报。
 */
class RealAuditorTest {

    private final RealAuditor auditor = new RealAuditor();

    @Test
    void f8_statementOnlyRowReportedAsBreak() {
        // 渠道账单多一笔 CH-AUD-X1 12000（长款），账本无对应发生额
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"));
        List<ChannelStatement> statements = List.of(
                new ChannelStatement("CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"),
                new ChannelStatement("CH-AUD-X1", 12000L, "CNY", "SUCCEEDED"));
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-1", "ik-1", "PAYMENT", "PM-AUD-0001", "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(1L, "DEBIT", 10000L, "PAYMENT_CAPTURE",
                                "PAYMENT", "PM-AUD-0001"),
                        new LedgerPostingView.LedgerEntryView(2L, "CREDIT", 10000L, "PAYMENT_CAPTURE",
                                "PAYMENT", "PM-AUD-0001"))));

        List<AuditDifference> differences = auditor.audit(facts, statements, postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
        assertThat(differences.get(0).getSourceId()).isEqualTo("CH-AUD-X1");
    }

    @Test
    void refundDirectionUsesSignedComparison() {
        List<CertificateFact> facts = List.of(
                new CertificateFact("REFUND", "RF-AUD-0001", "CH-RF-0001", 3000L, "CNY", "SUCCEEDED"));
        List<ChannelStatement> statements = List.of(
                new ChannelStatement("CH-RF-0001", 3000L, "CNY", "SUCCEEDED"));
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-3", "ik-3", "REFUND", "RF-AUD-0001", "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(2L, "DEBIT", 3000L, "REFUND", "REFUND", "RF-AUD-0001"),
                        new LedgerPostingView.LedgerEntryView(1L, "CREDIT", 3000L, "REFUND", "REFUND", "RF-AUD-0001"))));

        List<AuditDifference> differences = auditor.audit(facts, statements, postings);
        assertThat(differences).isEmpty();
    }

    @Test
    void amountMismatchReported() {
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"));
        List<ChannelStatement> statements = List.of(
                new ChannelStatement("CH-AUD-0001", 9000L, "CNY", "SUCCEEDED"));
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-1", "ik-1", "PAYMENT", "PM-AUD-0001", "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(1L, "DEBIT", 10000L, "PAYMENT_CAPTURE",
                                "PAYMENT", "PM-AUD-0001"),
                        new LedgerPostingView.LedgerEntryView(2L, "CREDIT", 10000L, "PAYMENT_CAPTURE",
                                "PAYMENT", "PM-AUD-0001"))));

        List<AuditDifference> differences = auditor.audit(facts, statements, postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
    }
}
