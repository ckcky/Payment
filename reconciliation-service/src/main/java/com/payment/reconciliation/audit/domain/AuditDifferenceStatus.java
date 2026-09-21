package com.payment.reconciliation.audit.domain;

/**
 * 审计差异状态机（spec 017 plan §7.3 + spec 032 §7.2 处置动作/结果分态，H-032-3）：
 * PENDING → SUSPENDED（挂账）→ ADJUSTING（瞬时）→ ADJUSTED（调账成功）/ ADJUST_FAILED（失败，可见）
 * → VERIFIED（recheck 通过）→ RESOLVED（人工确认收口）。
 * 任意未收口环节可回退重处置（ADJUST_FAILED 亦可重新发起处置）。
 * 「未收口」= PENDING/SUSPENDED/ADJUSTING/ADJUSTED（plan §2.3 口径 = spec §8.4 三态 + ADJUSTED），
 * 关批被拒；ADJUST_FAILED 不计入未收口（失败已独立事务留痕于失败台账，可见可重试，不静默吞掉）。
 */
public enum AuditDifferenceStatus {
    PENDING,
    SUSPENDED,
    ADJUSTING,
    ADJUSTED,
    ADJUST_FAILED,
    VERIFIED,
    RESOLVED;

    /** 是否属于「未收口」（关批门禁口径，FR-018 + spec 032 §8.4 / plan §2.3）。 */
    public boolean unclosed() {
        return this == PENDING || this == SUSPENDED || this == ADJUSTING || this == ADJUSTED;
    }
}
