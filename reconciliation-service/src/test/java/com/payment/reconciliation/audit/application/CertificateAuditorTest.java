package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 账证核对单测（spec 017 / T028，031 科目码口径）：F1 平账 + F2~F5 故障 +
 * 金额 / 币种 / 方向边界 + PENDING 不判差异 + recheck 处置净额。
 *
 * <p>posting 腿（对齐账本 §7 规则，手续费 MVP=0）：PAYMENT=Dr CHANNEL_RECEIVABLE/Cr MP；
 * REFUND 反向；SETTLEMENT=Dr MP/Cr SETTLEMENT_PAYABLE（sourceId=batchNo）；
 * 处置（SUSPEND 欠记）=Dr BANK_CASH/Cr SUSPENSE。</p>
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
        // actual = 两条重复 posting 借方合计（差额 = 多记部分）
        assertThat(differences.get(0).getActualAmountMinor()).isEqualTo(50000L);
    }

    @Test
    void currencyMismatchReported() {
        List<LedgerPostingView> postings = List.of(
                postingWithCurrency("LP-USD", "PAYMENT", "PM-AUD-0001", "USD", 10000L));
        List<AuditDifference> differences = auditor.audit(List.of(baseFacts.get(0)), postings);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).getKind()).isEqualTo(AuditDifferenceKind.CURRENCY_MISMATCH);
    }

    @Test
    void directionMismatchReported() {
        // 退款来源却记成正向资金腿（Dr CHANNEL_RECEIVABLE +3000，期望 −3000）
        List<LedgerPostingView> wrongLeg = List.of(refundAsDebitFunds("LP-3W", "RF-AUD-0001", 3000L));
        List<AuditDifference> differences = auditor.audit(List.of(baseFacts.get(2)), wrongLeg);
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
    void settlementAmountJudgedByGrossDebit() {
        // SETTLEMENT 不走资金科目方向核对，金额（借方合计 = MP 腿）核对通过
        CertificateFact settlement = new CertificateFact("SETTLEMENT", "SB-AUD-0001", "SB-AUD-0001",
                21750L, "CNY", "SUCCEEDED");
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-SB", "MERCHANT_SETTLEMENT", "MERCHANT_SETTLEMENT:SB-AUD-0001",
                        "SETTLEMENT", "SB-AUD-0001", "CNY", List.of(
                        entry("MERCHANT_PAYABLE", "DEBIT", 21750L),
                        entry("SETTLEMENT_PAYABLE", "CREDIT", 21750L))));
        assertThat(auditor.audit(List.of(settlement), postings)).isEmpty();
    }

    @Test
    void recheckBalancedAfterSuspenseAndTransfer() {
        // F2 挂账 + 转出后：处置（RECONCILIATION）分录资金腿净影响 = +8000 → sourceBalanced
        CertificateFact missing = new CertificateFact("PAYMENT", "PM-AUD-0003", "CH-AUD-0003",
                8000L, "CNY", "SUCCEEDED");
        List<LedgerPostingView> adjustments = List.of(
                new LedgerPostingView("LP-S1", "ADJUSTMENT", "ADJUSTMENT:AD-1", "RECONCILIATION", "AD-1",
                        "CNY", List.of(
                        entry("BANK_CASH", "DEBIT", 8000L),
                        entry("SUSPENSE", "CREDIT", 8000L))));
        assertThat(auditor.sourceBalanced(missing, List.of(), adjustments)).isTrue();
        assertThat(auditor.sourceCustomerCashNet("PAYMENT", "PM-AUD-0003", List.of(), adjustments))
                .isEqualTo(8000L);
    }

    @Test
    void recheckSettlementWithDispositionCorrection() {
        // SETTLEMENT recheck：MP 腿带符号净额 + 处置现金腿修正后对平
        CertificateFact settlement = new CertificateFact("SETTLEMENT", "SB-AUD-0002", "SB-AUD-0002",
                9000L, "CNY", "SUCCEEDED");
        List<LedgerPostingView> postings = List.of(
                new LedgerPostingView("LP-SB2", "MERCHANT_SETTLEMENT", "MERCHANT_SETTLEMENT:SB-AUD-0002",
                        "SETTLEMENT", "SB-AUD-0002", "CNY", List.of(
                        entry("MERCHANT_PAYABLE", "DEBIT", 10000L),
                        entry("SETTLEMENT_PAYABLE", "CREDIT", 10000L))));
        // 初始 10000 ≠ 9000 未对平；补一笔处置（Cr BANK_CASH 1000 → 现金腿 −1000）后对平
        assertThat(auditor.sourceBalanced(settlement, postings, List.of())).isFalse();
        List<LedgerPostingView> adjustments = List.of(
                new LedgerPostingView("LP-S2", "ADJUSTMENT", "ADJUSTMENT:AD-2", "RECONCILIATION", "AD-2",
                        "CNY", List.of(
                        entry("SUSPENSE", "DEBIT", 1000L),
                        entry("BANK_CASH", "CREDIT", 1000L))));
        assertThat(auditor.sourceBalanced(settlement, postings, adjustments)).isTrue();
    }

    // ---- helpers ----

    private static LedgerPostingView.LedgerEntryView entry(String accountCode, String direction, long amount) {
        return new LedgerPostingView.LedgerEntryView(1L, accountCode, "PLATFORM", "LEDGER", direction, amount);
    }

    /** 平账组 posting：PAYMENT=Dr CHANNEL_RECEIVABLE/Cr MP；REFUND 反向。 */
    private LedgerPostingView posting(String postingNo, String sourceType, String sourceId, long amount) {
        return postingWithCurrency(postingNo, sourceType, sourceId, "CNY", amount);
    }

    private LedgerPostingView postingWithCurrency(String postingNo, String sourceType, String sourceId,
                                                  String currency, long amount) {
        boolean refund = "REFUND".equals(sourceType);
        List<LedgerPostingView.LedgerEntryView> entries = List.of(
                entry(refund ? "MERCHANT_PAYABLE" : "CHANNEL_RECEIVABLE", "DEBIT", amount),
                entry(refund ? "CHANNEL_RECEIVABLE" : "MERCHANT_PAYABLE", "CREDIT", amount));
        return new LedgerPostingView(postingNo, sourceType + "_capture", sourceType + ":" + sourceId,
                sourceType, sourceId, currency, entries);
    }

    /** 方向错误样本：REFUND 来源却记成正向资金腿（Dr CHANNEL_RECEIVABLE）。 */
    private LedgerPostingView refundAsDebitFunds(String postingNo, String sourceId, long amount) {
        return new LedgerPostingView(postingNo, "REFUND", "REFUND:" + sourceId, "REFUND", sourceId, "CNY",
                List.of(
                        entry("CHANNEL_RECEIVABLE", "DEBIT", amount),
                        entry("MERCHANT_PAYABLE", "CREDIT", amount)));
    }
}
