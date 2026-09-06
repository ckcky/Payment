package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.domain.ChannelStatement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A3 账实核对（spec 017 / FR-008）：账本资金科目发生额 ↔ 渠道账单。
 * 与既有「业务台账 ↔ 渠道账单」链路并列（006 链路保持不动，NFR-006）。
 *
 * <p>口径：按渠道引用（channelReference）比对 CUSTOMER_CASH 带符号发生额——
 * 支付借方为正、退款贷方为负；账本无对应发生额（长款）或金额不符均报差异。</p>
 */
@Component
public class RealAuditor {

    private static final long CUSTOMER_CASH_ID = 1L;

    /**
     * @param facts      已确认业务事实（含渠道引用，用于把 posting 关联到渠道口径）
     * @param statements 渠道账单（006 加载器口径）
     * @param postings   账本全部分录
     */
    public List<AuditDifference> audit(List<CertificateFact> facts,
                                       List<ChannelStatement> statements,
                                       List<LedgerPostingView> postings) {
        List<AuditDifference> differences = new ArrayList<>();

        // 渠道引用 → 账本资金科目带符号发生额（经由业务事实把 posting.sourceId 关联到 reference）
        Map<String, Long> ledgerByReference = new HashMap<>();
        Map<String, CertificateFact> factBySource = new HashMap<>();
        for (CertificateFact fact : facts) {
            if (fact.confirmed() && fact.reference() != null) {
                factBySource.put(fact.sourceType() + "|" + fact.sourceId(), fact);
            }
        }
        for (LedgerPostingView posting : postings) {
            CertificateFact fact = factBySource.get(posting.sourceType() + "|" + posting.sourceId());
            if (fact == null) {
                continue;
            }
            ledgerByReference.merge(fact.reference(),
                    signedCustomerCash(posting, fact.sourceType()), Long::sum);
        }

        for (ChannelStatement statement : statements) {
            if (!"SUCCEEDED".equals(statement.status())) {
                continue;
            }
            long ledgerAmount = ledgerByReference.getOrDefault(statement.reference(), 0L);
            // 渠道账单口径：支付为正、退款为负（statement 本身不区分方向，类型由事实侧定义）
            boolean refundRow = isRefundReference(statement.reference(), facts);
            long statementSigned = refundRow ? -statement.amountMinor() : statement.amountMinor();
            if (ledgerAmount != statementSigned) {
                differences.add(AuditDifference.of(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK, "CHANNEL",
                        statement.reference(), statement.reference(), statementSigned, ledgerAmount,
                        statement.currencyCode(), "账实不符：渠道账单 " + statementSigned + " / 账本资金科目 "
                                + ledgerAmount));
            }
        }
        return differences;
    }

    /** 退款方向的账单行：与任一 REFUND 事实的渠道引用匹配。 */
    private boolean isRefundReference(String reference, List<CertificateFact> facts) {
        return facts.stream().anyMatch(f -> "REFUND".equals(f.sourceType())
                && f.confirmed() && reference.equals(f.reference()));
    }

    private long signedCustomerCash(LedgerPostingView posting, String sourceType) {
        return posting.entries().stream()
                .filter(e -> e.accountId() == CUSTOMER_CASH_ID)
                .mapToLong(e -> "DEBIT".equals(e.direction()) ? e.amountMinor() : -e.amountMinor())
                .sum();
    }
}
