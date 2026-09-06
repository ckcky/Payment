package com.payment.reconciliation.audit.api;

/**
 * SUSPENSE（待处理差错款）科目余额（SC-016：应 == Σ 未收口差异挂账净额）。
 */
public record SuspenseBalanceResponse(long amountMinor, String currency) {
}
