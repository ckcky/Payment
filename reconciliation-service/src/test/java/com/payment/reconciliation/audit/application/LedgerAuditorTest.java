package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2 账账核对单测（spec 017 / T034，031 科目码口径）：借贷平衡、科目勾稽（含 F6 科目记错、
 * SUSPENSE 勾稽）、跨账（F7）。勾稽按科目码聚合全部实例（修复旧 accountId=2 分户盲区）。
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
                postingSettlement("LP-4", "SB-AUD-0001", 21750L));
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
        // F6：250 被误记入 FEE_REVENUE（本应进 MERCHANT_PAYABLE），借贷仍平衡
        List<LedgerPostingView> postings = List.of(
                postingPayment("LP-1", "PM-AUD-0001", 10000L),
                // PM-AUD-0002: 借 CHANNEL_RECEIVABLE 25000 / 贷 MP 24750 + 贷 FEE_REVENUE 250
                new LedgerPostingView("LP-2", "PAYMENT_CAPTURE", "PAYMENT_CAPTURE:PM-AUD-0002",
                        "PAYMENT", "PM-AUD-0002", "CNY", List.of(
                        entry("CHANNEL_RECEIVABLE", "DEBIT", 25000L),
                        entry("MERCHANT_PAYABLE", "CREDIT", 24750L),
                        entry("FEE_REVENUE", "CREDIT", 250L))),
                postingRefund("LP-3", "RF-AUD-0001", 3000L),
                postingSettlement("LP-4", "SB-AUD-0001", 21750L));
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
                // 账本 SUSPENSE 贷方 1000，但台账未收口挂账为 800
                new LedgerPostingView("LP-S", "ADJUSTMENT", "ADJUSTMENT:AD-1", "RECONCILIATION", "AD-1",
                        "CNY", List.of(
                        entry("BANK_CASH", "DEBIT", 1000L),
                        entry("SUSPENSE", "CREDIT", 1000L))));
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));
        List<AuditDifference> differences = auditor.audit(facts, settlements, postings, balance, 800L);
        assertThat(differences).anyMatch(d -> d.getKind() == AuditDifferenceKind.ACCOUNT_RECON_BREAK
                && "SUSPENSE".equals(d.getSourceId()));
    }

    @Test
    void f7_crossLedgerMismatchReported() {
        List<LedgerPostingView> postings = List.of(
                postingSettlement("LP-4", "SB-AUD-0001", 21000L)); // 结算净额 21750 vs 账本 21000
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));
        List<AuditDifference> differences = auditor.audit(facts, settlements, postings, balance, 0L);
        assertThat(differences).anyMatch(d -> d.getKind() == AuditDifferenceKind.CROSS_LEDGER_MISMATCH
                && "SB-AUD-0001".equals(d.getSourceId()));
    }

    @Test
    void merchantPayableAggregatesAcrossMerchantInstances() {
        // 031 分户聚合：多商户实例（ownerId 不同）按科目码合计，不再只看单一数值 accountId
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-M1", "PAYMENT_CAPTURE", "PAYMENT_CAPTURE:PM-1", "PAYMENT", "PM-1",
                        "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(1L, "CHANNEL_RECEIVABLE", "CHANNEL", "ALIPAY",
                                "DEBIT", 6000L),
                        new LedgerPostingView.LedgerEntryView(2L, "MERCHANT_PAYABLE", "MERCHANT", "M001",
                                "CREDIT", 6000L))),
                new LedgerPostingView("LP-M2", "PAYMENT_CAPTURE", "PAYMENT_CAPTURE:PM-2", "PAYMENT", "PM-2",
                        "CNY", List.of(
                        new LedgerPostingView.LedgerEntryView(3L, "CHANNEL_RECEIVABLE", "CHANNEL", "WECHAT",
                                "DEBIT", 4000L),
                        new LedgerPostingView.LedgerEntryView(4L, "MERCHANT_PAYABLE", "MERCHANT", "M002",
                                "CREDIT", 4000L))));
        LedgerBalance balance = new LedgerBalance(true, Map.of("CNY", 0L));
        List<CertificateFact> twoMerchants = List.of(
                new CertificateFact("PAYMENT", "PM-1", null, 6000L, "CNY", "SUCCEEDED"),
                new CertificateFact("PAYMENT", "PM-2", null, 4000L, "CNY", "SUCCEEDED"));

        List<AuditDifference> differences = auditor.audit(twoMerchants, List.of(), postings, balance, 0L);
        assertThat(differences).isEmpty(); // 推导 10000 = 实算 6000+4000（跨户聚合）
    }

    // ---- helpers ----

    private static LedgerPostingView.LedgerEntryView entry(String accountCode, String direction, long amount) {
        return new LedgerPostingView.LedgerEntryView(1L, accountCode, "PLATFORM", "LEDGER", direction, amount);
    }

    private LedgerPostingView postingPayment(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "PAYMENT_CAPTURE", "PAYMENT_CAPTURE:" + sourceId, "PAYMENT", sourceId,
                "CNY", List.of(
                entry("CHANNEL_RECEIVABLE", "DEBIT", amount),
                entry("MERCHANT_PAYABLE", "CREDIT", amount)));
    }

    private LedgerPostingView postingRefund(String no, String sourceId, long amount) {
        return new LedgerPostingView(no, "REFUND", "REFUND:" + sourceId, "REFUND", sourceId, "CNY", List.of(
                entry("MERCHANT_PAYABLE", "DEBIT", amount),
                entry("CHANNEL_RECEIVABLE", "CREDIT", amount)));
    }

    /** MERCHANT_SETTLEMENT：sourceId=batchNo（M1），Dr MP 带符号净额 = 批次净额。 */
    private LedgerPostingView postingSettlement(String no, String batchNo, long amount) {
        return new LedgerPostingView(no, "MERCHANT_SETTLEMENT", "MERCHANT_SETTLEMENT:" + batchNo,
                "SETTLEMENT", batchNo, "CNY", List.of(
                entry("MERCHANT_PAYABLE", "DEBIT", amount),
                entry("SETTLEMENT_PAYABLE", "CREDIT", amount)));
    }
}
