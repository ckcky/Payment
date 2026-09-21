package com.payment.reconciliation.audit.application;

import com.payment.common.dto.rpc.AccountCode;
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
 *
 * <p>spec 031 适配：资金腿口径 = 平台侧「收付通道」科目
 * （CUSTOMER_CASH=历史、BANK_CASH=新现金腿、CHANNEL_RECEIVABLE=支付事件的资金腿——
 * 手续费 MVP 传 0，CHANNEL_RECEIVABLE 净额 = gross，与事实金额可对）。
 * 处置分录来源：历史 {@code ADJUSTMENT} 与新 {@code RECONCILIATION} 并列识别。</p>
 */
@Component
public class CertificateAuditor {

    /** 资金腿科目集（方向核对口径，见类注释）。 */
    private static final String[] FUND_CODES = {
            AccountCode.CUSTOMER_CASH.name(), AccountCode.BANK_CASH.name(),
            AccountCode.CHANNEL_RECEIVABLE.name()};
    /** 平台现金科目（处置修正口径，与 017 的 CUSTOMER_CASH 腿语义一致）。 */
    private static final String[] CASH_CODES = {
            AccountCode.CUSTOMER_CASH.name(), AccountCode.BANK_CASH.name()};

    /** 处置（挂账/调账）产生的 posting 来源域：历史 ADJUSTMENT + 031 起 RECONCILIATION。 */
    static boolean isDispositionSource(String sourceType) {
        return "ADJUSTMENT".equals(sourceType) || "RECONCILIATION".equals(sourceType);
    }

    /** 执行账证核对：业务已确认事实 ↔ 账本分录。 */
    public List<AuditDifference> audit(List<CertificateFact> facts, List<LedgerPostingView> postings) {
        List<AuditDifference> differences = new ArrayList<>();

        // 账本侧索引：(sourceType, sourceId) → postings；只看业务来源（处置分录是产物，不参与）
        Map<String, List<LedgerPostingView>> bySource = new HashMap<>();
        Set<String> businessPostingKeys = new HashSet<>();
        for (LedgerPostingView posting : postings) {
            if (isDispositionSource(posting.sourceType())) {
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
            // 方向核对：资金腿科目符号——支付为正，退款为负；
            // SETTLEMENT 不走资金科目（应付转结算应付），金额已核对即通过
            if (!"SETTLEMENT".equals(fact.sourceType())) {
                long signed = fundsSigned(posting);
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
        // SETTLEMENT：核对结算 posting 的 MERCHANT_PAYABLE 带符号净额（含处置修正）与批次净额。
        // 031/M1 后结算事件 sourceId=batchNo；Dr MP=+net、负净额 Cr MP=-|net|，带符号恒等。
        if ("SETTLEMENT".equals(fact.sourceType())) {
            long posted = 0;
            for (LedgerPostingView posting : postings) {
                if ("SETTLEMENT".equals(posting.sourceType()) && fact.sourceId().equals(posting.sourceId())) {
                    posted += posting.signedForCodes(AccountCode.MERCHANT_PAYABLE.name());
                }
            }
            // 处置修正以平台现金腿净影响折算（挂/转的现金腿），口径同 017。
            for (LedgerPostingView posting : adjustmentPostings) {
                posted += posting.signedForCodes(CASH_CODES);
            }
            return posted == fact.amountMinor();
        }
        long expectedSigned = "REFUND".equals(fact.sourceType()) ? -fact.amountMinor() : fact.amountMinor();
        long ledgerSigned = sourceCustomerCashNet(fact.sourceType(), fact.sourceId(), postings, adjustmentPostings);
        return ledgerSigned == expectedSigned;
    }

    /** 指定来源的资金腿科目净影响：业务来源分录 + 处置（ADJUSTMENT/RECONCILIATION）分录。 */
    public long sourceCustomerCashNet(String sourceType, String sourceId,
                                      List<LedgerPostingView> postings,
                                      List<LedgerPostingView> adjustmentPostings) {
        long ledgerSigned = 0;
        for (LedgerPostingView posting : postings) {
            if (sourceType.equals(posting.sourceType()) && sourceId.equals(posting.sourceId())) {
                ledgerSigned += fundsSigned(posting);
            }
        }
        // ADJUSTMENT 分录与该差异的关联由处置台账（audit_adjustments.difference_id）承载：
        // 挂账 / 补记 / 红冲的资金科目腿都体现在这里。
        for (LedgerPostingView posting : adjustmentPostings) {
            ledgerSigned += fundsSigned(posting);
        }
        return ledgerSigned;
    }

    private long fundsSigned(LedgerPostingView posting) {
        return posting.signedForCodes(FUND_CODES);
    }

    private String key(String sourceType, String sourceId) {
        return sourceType + "|" + sourceId;
    }
}
