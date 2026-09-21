package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.domain.ChannelStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3 账实核对单测（spec 017 / T038，031 科目码口径）：F8 长款 / 金额不符 / 重复流水 /
 * 正式账单反向短款；正常组不误报。
 *
 * <p>资金腿口径（费 0）：PAYMENT=Dr CHANNEL_RECEIVABLE（+）、REFUND=Cr CHANNEL_RECEIVABLE（−）。</p>
 */
class RealAuditorTest {

    private final RealAuditor auditor = new RealAuditor();

    @Test
    void f8_statementOnlyRowReportedAsBreak() {
        // 渠道账单多一笔 CH-AUD-X1 12000（长款），账本无对应发生额
        List<LedgerPostingView> postings = List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L));
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED")),
                List.of(new ChannelStatement("CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"),
                        new ChannelStatement("CH-AUD-X1", 12000L, "CNY", "SUCCEEDED")),
                postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
        assertThat(differences.get(0).getSourceId()).isEqualTo("CH-AUD-X1");
    }

    @Test
    void refundDirectionUsesSignedComparison() {
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-3", "REFUND", "REFUND:RF-AUD-0001", "REFUND", "RF-AUD-0001", "CNY",
                        List.of(
                                entry("MERCHANT_PAYABLE", "DEBIT", 3000L),
                                entry("CHANNEL_RECEIVABLE", "CREDIT", 3000L))));
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("REFUND", "RF-AUD-0001", "CH-RF-0001", 3000L, "CNY", "SUCCEEDED")),
                List.of(new ChannelStatement("CH-RF-0001", 3000L, "CNY", "SUCCEEDED")),
                postings);
        assertThat(differences).isEmpty();
    }

    @Test
    void amountMismatchReported() {
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED")),
                List.of(new ChannelStatement("CH-AUD-0001", 9000L, "CNY", "SUCCEEDED")),
                List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L)));
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
    }

    @Test
    void shortStatementDetectedOnlyWithOfficialStatementFile() {
        // 正式账单文件缺行（短款/单边账）：账本有发生额、账单无对应行 → 反向检出
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"));
        List<LedgerPostingView> postings = List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L));

        List<AuditDifference> official = auditor.audit(facts, List.of(), postings, true);
        assertThat(official).hasSize(1);
        assertThat(official.get(0).getKind()).isEqualTo(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
        assertThat(official.get(0).getDetail()).contains("短款");

        // 回退默认 fixture（非正式账单）：不反向比对，不误报
        List<AuditDifference> fallback = auditor.audit(facts, List.of(), postings, false);
        assertThat(fallback).isEmpty();
    }

    @Test
    void duplicatedStatementRowsDetected() {
        // 同 reference 两行（重复投递形态）：即使金额与账本一致也必须留痕
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"));
        List<ChannelStatement> statements = List.of(
                new ChannelStatement("CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"),
                new ChannelStatement("CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"));
        List<LedgerPostingView> postings = List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L));

        List<AuditDifference> differences = auditor.audit(facts, statements, postings, true);
        // 第一行正向对平；重复行检出（ledgerByReference 已合计 20000 的行只报重复）
        assertThat(differences).isNotEmpty();
        assertThat(differences).allMatch(d -> d.getKind() == AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
        assertThat(differences).anyMatch(d -> d.getDetail().contains("重复"));
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
}
