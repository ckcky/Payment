package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A1 账证核对（spec 017 / FR-001、FR-002）：按 (source_type, source_id) 对支付 / 退款 / 结算
 * 三来源做双向比对，产出六类差异。只读比对，绝不修正任何数据。
 */
@Component
public class CertificateAuditor {

    private static final String CUSTOMER_CASH_ACCOUNT_ID = "1";

    /** 执行账证核对：业务已确认事实 ↔ 账本分录。 */
    public List<AuditDifference> audit(List<CertificateFact> facts, List<LedgerPostingView> postings) {
        List<AuditDifference> differences = new ArrayList<>();

        // 账本侧索引：(sourceType, sourceId) → postings；只看业务来源（ADJUSTMENT 是处置产物，不参与）
        Map<String, List<LedgerPostingView>> bySource = new HashMap<>();
        Set<String> businessPostingKeys = new HashSet<>();
        for (LedgerPostingView posting : postings) {
            if ("ADJUSTMENT".equals(posting.sourceType())) {
                continue;
            }
            bySource.computeIfAbsent(key(posting.sourceType(), posting.sourceId()), k -> new ArrayList<>())
                    .add(posting);
            businessPostingKeys.add(key(posting.sourceType(), posting.sourceId()));
        }

        Set<String> confirmedFactKeys = new HashSet<>();
        for (CertificateFact fact : facts) {
            if (!fact.confirmed()) {
                continue; // FR-012：PENDING / 处理中不判差异（时点一致性）
            }
            confirmedFactKeys.add(key(fact.sourceType(), fact.sourceId()));
            List<LedgerPostingView> matched = bySource.getOrDefault(key(fact.sourceType(), fact.sourceId()), List.of());

            if (matched.isEmpty()) {
                differences.add(AuditDifference.of(AuditDifferenceKind.MISSING_POSTING, fact.sourceType(),
                        fact.sourceId(), fact.reference(), fact.amountMinor(), 0L, fact.currency(),
                        "业务已确认但账本无分录（漏记账）"));
                continue;
            }
            if (matched.size() > 1) {
                // actual = 全部重复 posting 金额合计（差额 = 多记部分，可挂账处置）
                long totalDebit = matched.stream().mapToLong(LedgerPostingView::debitTotal).sum();
                differences.add(AuditDifference.of(AuditDifferenceKind.DUPLICATE_POSTING, fact.sourceType(),
                        fact.sourceId(), fact.reference(), fact.amountMinor(), totalDebit,
                        fact.currency(), "同一来源存在 " + matched.size() + " 条 posting（幂等被击穿）"));
                continue;
            }

            LedgerPostingView posting = matched.get(0);
            long postedAmount = posting.debitTotal();
            if (!posting.currency().equals(fact.currency())) {
                differences.add(AuditDifference.of(AuditDifferenceKind.CURRENCY_MISMATCH, fact.sourceType(),
                        fact.sourceId(), fact.reference(), fact.amountMinor(), postedAmount,
                        posting.currency(), "币种不符：业务 " + fact.currency() + " / 账本 " + posting.currency()));
                continue;
            }
            if (postedAmount != fact.amountMinor()) {
                differences.add(AuditDifference.of(AuditDifferenceKind.AMOUNT_MISMATCH, fact.sourceType(),
                        fact.sourceId(), fact.reference(), fact.amountMinor(), postedAmount, fact.currency(),
                        "金额不符：业务 " + fact.amountMinor() + " / 账本 " + postedAmount));
                continue;
            }
            // 方向核对：资金科目（CUSTOMER_CASH）符号——支付借方为正，退款贷方为负；
            // SETTLEMENT 不走客户资金科目（借应付/贷结算应付），金额已核对即通过
            if (!"SETTLEMENT".equals(fact.sourceType())) {
                long signed = signedCustomerCash(posting);
                long expectedSigned = "REFUND".equals(fact.sourceType()) ? -fact.amountMinor() : fact.amountMinor();
                if (signed != expectedSigned) {
                    differences.add(AuditDifference.of(AuditDifferenceKind.DIRECTION_MISMATCH, fact.sourceType(),
                            fact.sourceId(), fact.reference(), expectedSigned, signed, fact.currency(),
                            "资金科目方向不符：期望 " + expectedSigned + " / 实际 " + signed));
                }
            }
        }

        // 反向：账本有、业务无（孤儿分录）
        for (Map.Entry<String, List<LedgerPostingView>> entry : bySource.entrySet()) {
            if (!confirmedFactKeys.contains(entry.getKey())) {
                LedgerPostingView posting = entry.getValue().get(0);
                String[] parts = entry.getKey().split("\\|", 2);
                differences.add(AuditDifference.of(AuditDifferenceKind.ORPHAN_POSTING, parts[0], parts[1],
                        null, 0L, posting.debitTotal(), posting.currency(), "账本分录无对应业务事实（孤儿分录）"));
            }
        }
        return differences;
    }

    /** recheck（FR-017）：该来源在当前账本视图下是否已对平。 */
    public boolean sourceBalanced(CertificateFact fact, List<LedgerPostingView> postings,
                                  List<LedgerPostingView> adjustmentPostings) {
        // SETTLEMENT：核对结算 posting 金额合计（含处置修正）与批次净额
        if ("SETTLEMENT".equals(fact.sourceType())) {
            long posted = 0;
            for (LedgerPostingView posting : postings) {
                if ("SETTLEMENT".equals(posting.sourceType()) && fact.sourceId().equals(posting.sourceId())) {
                    posted += posting.debitTotal();
                }
            }
            // 处置分录以资金科目净影响折算差额修正：多记挂账（借 SUSPENSE / 贷客户资金）→ 冲减；
            // 转出（借应付 / 贷 SUSPENSE）资金净 0，不影响。
            for (LedgerPostingView posting : adjustmentPostings) {
                posted += posting.signedAmountForAccount(1L);
            }
            return posted == fact.amountMinor();
        }
        long expectedSigned = "REFUND".equals(fact.sourceType()) ? -fact.amountMinor() : fact.amountMinor();
        long ledgerSigned = sourceCustomerCashNet(fact.sourceType(), fact.sourceId(), postings, adjustmentPostings);
        return ledgerSigned == expectedSigned;
    }

    /** 指定来源的资金科目（CUSTOMER_CASH）净影响：业务来源分录 + 处置（ADJUSTMENT）分录。 */
    public long sourceCustomerCashNet(String sourceType, String sourceId,
                                      List<LedgerPostingView> postings,
                                      List<LedgerPostingView> adjustmentPostings) {
        long ledgerSigned = 0;
        for (LedgerPostingView posting : postings) {
            if (sourceType.equals(posting.sourceType()) && sourceId.equals(posting.sourceId())) {
                ledgerSigned += signedCustomerCash(posting);
            }
        }
        // ADJUSTMENT 分录与该差异的关联由处置台账（audit_adjustments.difference_id）承载：
        // 挂账 / 补记 / 红冲的资金科目腿都体现在这里。
        for (LedgerPostingView posting : adjustmentPostings) {
            ledgerSigned += signedCustomerCash(posting);
        }
        return ledgerSigned;
    }

    private long signedCustomerCash(LedgerPostingView posting) {
        return posting.entries().stream()
                .filter(e -> CUSTOMER_CASH_ACCOUNT_ID.equals(String.valueOf(e.accountId())))
                .mapToLong(e -> "DEBIT".equals(e.direction()) ? e.amountMinor() : -e.amountMinor())
                .sum();
    }

    private String key(String sourceType, String sourceId) {
        return sourceType + "|" + sourceId;
    }
}
