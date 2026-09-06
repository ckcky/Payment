package com.payment.reconciliation.audit.application;

import java.util.Map;

/**
 * 账本借贷平衡视图（ledger /internal/ledger/balance）。
 */
public record LedgerBalance(boolean balanced, Map<String, Long> diffByCurrency) {
}
