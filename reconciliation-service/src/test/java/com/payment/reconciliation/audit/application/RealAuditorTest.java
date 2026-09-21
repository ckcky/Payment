package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.statement.StatementLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3 账实核对单测（spec 017 / T038，031 科目码口径 + spec 032 typed 账单行）：
 * F8 长款 / 金额不符 / 重复流水 / 正式账单反向短款 / 渠道行非成功转可查询差异；
 * 正常组不误报。
 *
 * <p>资金腿口径（费 0）：PAYMENT=Dr CHANNEL_RECEIVABLE（+）、REFUND=Cr CHANNEL_RECEIVABLE（−）。
 * typed 行方向由 {@code referenceType} 显式给出（REFUND 为负）；FEE/SETTLEMENT 行不进资金腿比对。</p>
 */
class RealAuditorTest {

    private final RealAuditor auditor = new RealAuditor();

    @Test
    void f8_statementOnlyRowReportedAsBreak() {
        // 渠道账单多一笔 CH-AUD-X1 12000（长款），账本无对应发生额
        List<LedgerPostingView> postings = List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L));
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED")),
                List.of(line(1, "PAYMENT", "CH-AUD-0001", 10000L, "SUCCEEDED"),
                        line(2, "PAYMENT", "CH-AUD-X1", 12000L, "SUCCEEDED")),
                postings, true);
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
                List.of(line(1, "REFUND", "CH-RF-0001", 3000L, "SUCCEEDED")),
                postings, true);
        assertThat(differences).isEmpty();
    }

    @Test
    void amountMismatchReported() {
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED")),
                List.of(line(1, "PAYMENT", "CH-AUD-0001", 9000L, "SUCCEEDED")),
                List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L)), true);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK);
    }

    /** 静默跳过转可查询（spec 032 §1.2 证据 13）：渠道行未成功而平台已确认且账本有资金移动 ⇒ 明示差异。 */
    @Test
    void nonSucceededStatementRowWithLedgerMovementIsReported() {
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED")),
                List.of(line(1, "PAYMENT", "CH-AUD-0001", 10000L, "FAILED")),
                List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L)), true);
        // ① 行状态非 SUCCEEDED 而账本有资金移动 ⇒ 明示差异；② 正式账单缺行 ⇒ 反向短款差异（双表面留痕）
        assertThat(differences).hasSize(2);
        assertThat(differences).anyMatch(d -> d.getDetail().contains("FAILED"));
        assertThat(differences).anyMatch(d -> d.getDetail().contains("短款"));
    }

    /** FEE / SETTLEMENT 行不进资金腿比对（G3 事件对平，双计防线）。 */
    @Test
    void feeAndSettlementRowsAreSkippedFromFundLegComparison() {
        List<AuditDifference> differences = auditor.audit(
                List.of(new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED")),
                List.of(line(1, "PAYMENT", "CH-AUD-0001", 10000L, "SUCCEEDED"),
                        line(2, "FEE", "CH-FEE-1", 50L, "SUCCEEDED"),
                        line(3, "SETTLEMENT", "CH-STL-1", 9950L, "SUCCEEDED")),
                List.of(paymentPosting("LP-1", "PM-AUD-0001", 10000L)), true);
        assertThat(differences).isEmpty();
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

        // 无正式导入（非正式账单周期）：不反向比对，不误报
        List<AuditDifference> fallback = auditor.audit(facts, List.of(), postings, false);
        assertThat(fallback).isEmpty();
    }

    @Test
    void duplicatedStatementRowsDetected() {
        // 同 reference 两行（重复投递形态）：即使金额与账本一致也必须留痕
        List<CertificateFact> facts = List.of(
                new CertificateFact("PAYMENT", "PM-AUD-0001", "CH-AUD-0001", 10000L, "CNY", "SUCCEEDED"));
        List<StatementLine> statements = List.of(
                line(1, "PAYMENT", "CH-AUD-0001", 10000L, "SUCCEEDED"),
                line(2, "PAYMENT", "CH-AUD-0001", 10000L, "SUCCEEDED"));
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

    private static StatementLine line(int lineNo, String referenceType, String reference,
                                      long amountMinor, String status) {
        return new StatementLine(null, lineNo, "MOCK", "TXN-" + reference, referenceType, reference,
                "CHANNEL_TXN", "M-1", amountMinor, 0L, "CNY", status, null,
                referenceType + "," + reference + "," + amountMinor + ",CNY," + status);
    }

    private LedgerPostingView paymentPosting(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "PAYMENT_CAPTURE", "PAYMENT_CAPTURE:" + sourceId, "PAYMENT", sourceId,
                "CNY", List.of(
                entry("CHANNEL_RECEIVABLE", "DEBIT", amount),
                entry("MERCHANT_PAYABLE", "CREDIT", amount)));
    }
}
