package com.payment.reconciliation.audit.domain;

/**
 * 审计差异状态机（spec 017 plan §7.3）：
 * PENDING → SUSPENDED（挂账）→ ADJUSTED（调账）→ VERIFIED（recheck 通过）→ RESOLVED；
 * 任意未收口环节可回退重处置。PENDING/SUSPENDED/ADJUSTED 视为「未收口」，关批被拒。
 */
public enum AuditDifferenceStatus {
    PENDING,
    SUSPENDED,
    ADJUSTED,
    VERIFIED,
    RESOLVED;

    /** 是否属于「未收口」（关批门禁口径，FR-018）。 */
    public boolean unclosed() {
        return this == PENDING || this == SUSPENDED || this == ADJUSTED;
    }
}
