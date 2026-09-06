package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.audit.domain.SuspensePolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A2 账账核对（spec 017 / FR-005~FR-007）：分币种借贷平衡 + 科目勾稽 + 跨账。
 *
 * <p>勾稽公式（业务口径推导 vs 账本实算，容差 0）：
 * <ul>
 *   <li>{@code MERCHANT_PAYABLE ?= Σ已确认支付 − Σ已退款 − Σ已结算净额}（手续费 MVP 计 0，
 *       与 payment 侧 FeignLedgerPostingGateway 的 feeMinor=0 口径一致）</li>
 *   <li>{@code SUSPENSE ?= Σ 未收口差异挂账净额}（处置台账与账本互证，SC-016）</li>
 * </ul></p>
 */
@Component
public class LedgerAuditor {

    private static final long MERCHANT_PAYABLE_ID = 2L;
    private static final long SUSPENSE_ID = 5L;

    /**
     * @param facts              已确认业务事实（支付 / 退款）
     * @param settlementFacts    结算批次事实（仅 SUCCEEDED 参与勾稽）
     * @param postings           账本全部分录
     * @param balance            借贷平衡视图
     * @param unclosedSuspenseMinor 未收口差异挂账净额（audit 库口径）
     */
    public List<AuditDifference> audit(List<CertificateFact> facts,
                                       List<SettlementBatchFact> settlementFacts,
                                       List<LedgerPostingView> postings,
                                       LedgerBalance balance,
                                       long unclosedSuspenseMinor) {
        List<AuditDifference> differences = new ArrayList<>();

        // A2-1 借贷平衡（FR-005）
        if (balance != null) {
            for (Map.Entry<String, Long> e : balance.diffByCurrency().entrySet()) {
                if (e.getValue() != 0L) {
                    differences.add(AuditDifference.of(AuditDifferenceKind.BALANCE_BREAK, "LEDGER",
                            e.getKey(), null, 0L, e.getValue(), e.getKey(),
                            "借贷平衡差额非 0：" + e.getValue()));
                }
            }
        }

        // A2-2 科目勾稽（FR-006）
        long payableActual = 0;
        long suspenseActual = 0;
        for (LedgerPostingView posting : postings) {
            payableActual -= posting.signedAmountForAccount(MERCHANT_PAYABLE_ID);
            suspenseActual -= posting.signedAmountForAccount(SUSPENSE_ID); // 贷方为正（挂账口径）
        }
        long payableExpected = 0;
        for (CertificateFact fact : facts) {
            if (!fact.confirmed()) {
                continue;
            }
            payableExpected += switch (fact.sourceType()) {
                case "PAYMENT" -> fact.amountMinor();
                case "REFUND" -> -fact.amountMinor();
                default -> 0;
            };
        }
        for (SettlementBatchFact settlement : settlementFacts) {
            if (settlement.confirmed()) {
                payableExpected -= settlement.netMinor();
            }
        }
        if (payableActual != payableExpected) {
            differences.add(AuditDifference.of(AuditDifferenceKind.ACCOUNT_RECON_BREAK, "ACCOUNT",
                    SuspensePolicy.MERCHANT_PAYABLE, null, payableExpected, payableActual, "CNY",
                    "科目勾稽不符：推导 " + payableExpected + " / 实算 " + payableActual + "（容差 0）"));
        }
        if (suspenseActual != unclosedSuspenseMinor) {
            differences.add(AuditDifference.of(AuditDifferenceKind.ACCOUNT_RECON_BREAK, "ACCOUNT",
                    SuspensePolicy.SUSPENSE, null, unclosedSuspenseMinor, suspenseActual, "CNY",
                    "SUSPENSE 勾稽不符：未收口挂账净额 " + unclosedSuspenseMinor + " / 账本实算 " + suspenseActual));
        }

        // A2-3 跨账（FR-007）：结算批次净额 ↔ 该批次 ledger posting（sourceId = 批次 id）
        Map<String, List<LedgerPostingView>> settlementPostings = new HashMap<>();
        for (LedgerPostingView posting : postings) {
            if ("SETTLEMENT".equals(posting.sourceType())) {
                settlementPostings.computeIfAbsent(posting.sourceId(), k -> new ArrayList<>()).add(posting);
            }
        }
        for (SettlementBatchFact settlement : settlementFacts) {
            if (!settlement.confirmed() || settlement.netMinor() <= 0) {
                continue;
            }
            List<LedgerPostingView> matched = settlementPostings.getOrDefault(String.valueOf(settlement.id()), List.of());
            if (matched.isEmpty()) {
                continue; // 漏记由账证核对（MISSING_POSTING）覆盖，此处不重复报
            }
            long posted = matched.stream().mapToLong(LedgerPostingView::debitTotal).sum();
            if (posted != settlement.netMinor()) {
                differences.add(AuditDifference.of(AuditDifferenceKind.CROSS_LEDGER_MISMATCH, "SETTLEMENT",
                        settlement.batchNo(), settlement.batchNo(), settlement.netMinor(), posted,
                        settlement.currency(), "跨账不符：结算净额 " + settlement.netMinor() + " / 账本 "
                                + posted + "（批次 id=" + settlement.id() + "）"));
            }
        }
        return differences;
    }
}
