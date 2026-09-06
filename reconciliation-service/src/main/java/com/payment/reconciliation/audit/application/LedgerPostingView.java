package com.payment.reconciliation.audit.application;

import java.util.List;

/**
 * 账本分录只读视图（spec 017 / FR-004）：来自 ledger /internal/ledger/postings/all。
 */
public record LedgerPostingView(String postingNo, String idempotencyKey, String sourceType, String sourceId,
                                String currency, List<LedgerEntryView> entries) {

    /** 单条分录视图。 */
    public record LedgerEntryView(long accountId, String direction, long amountMinor,
                                  String entryType, String sourceType, String sourceId) {
    }

    /** posting 借方合计（= 贷方合计 = 该记账批次的金额）。 */
    public long debitTotal() {
        return entries.stream().filter(e -> "DEBIT".equals(e.direction())).mapToLong(LedgerEntryView::amountMinor).sum();
    }

    /** 指定科目的带符号发生额（DEBIT 为正、CREDIT 为负）。 */
    public long signedAmountForAccount(long accountId) {
        return entries.stream()
                .filter(e -> e.accountId() == accountId)
                .mapToLong(e -> "DEBIT".equals(e.direction()) ? e.amountMinor() : -e.amountMinor())
                .sum();
    }
}
