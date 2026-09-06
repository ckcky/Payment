package com.payment.reconciliation.audit.domain;

/**
 * 审计差异类型（FR-002 / FR-005~FR-009，共 11 类）。
 */
public enum AuditDifferenceKind {
    /** 账证：业务已确认但账本无分录（漏记账）。 */
    MISSING_POSTING,
    /** 账证：账本有分录但业务无事实（孤儿分录/多记）。 */
    ORPHAN_POSTING,
    /** 账证：两边金额不符。 */
    AMOUNT_MISMATCH,
    /** 账证：币种不符。 */
    CURRENCY_MISMATCH,
    /** 账证：借贷方向不符。 */
    DIRECTION_MISMATCH,
    /** 账证：同一 (source_type, source_id) 多条 posting（幂等被击穿）。 */
    DUPLICATE_POSTING,
    /** 账账：分币种借贷平衡差额非 0。 */
    BALANCE_BREAK,
    /** 账账：科目勾稽推导值 ≠ 账本实算（容差 0）。 */
    ACCOUNT_RECON_BREAK,
    /** 账账：结算批次净额与该批次 ledger posting 不符（跨账）。 */
    CROSS_LEDGER_MISMATCH,
    /** 账实：账本资金科目发生额与渠道账单不符。 */
    LEDGER_VS_STATEMENT_BREAK,
    /** 账表：报表口径与业务/账本回算不符。 */
    REPORT_MISMATCH;

    /** 缺省严重级（FR-011）。 */
    public AuditSeverity defaultSeverity() {
        return switch (this) {
            case MISSING_POSTING, ORPHAN_POSTING, DUPLICATE_POSTING, LEDGER_VS_STATEMENT_BREAK -> AuditSeverity.BLOCKER;
            case AMOUNT_MISMATCH, CURRENCY_MISMATCH, DIRECTION_MISMATCH, ACCOUNT_RECON_BREAK,
                    CROSS_LEDGER_MISMATCH, BALANCE_BREAK -> AuditSeverity.MAJOR;
            case REPORT_MISMATCH -> AuditSeverity.MINOR;
        };
    }

    /** 该差异是否属于账证核对（决定 recheck 时重跑哪个审计器）。 */
    public boolean isCertificateKind() {
        return this == MISSING_POSTING || this == ORPHAN_POSTING || this == AMOUNT_MISMATCH
                || this == CURRENCY_MISMATCH || this == DIRECTION_MISMATCH || this == DUPLICATE_POSTING;
    }
}
