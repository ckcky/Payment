package com.payment.entitlement.domain;

/**
 * 权益状态机。合法迁移只通过 {@link Entitlement} 的领域方法完成，
 * 禁止外部直接修改状态（见 Constitution 状态机约束）。
 */
public enum EntitlementStatus {
    PENDING_GRANT,
    AVAILABLE,
    PARTIALLY_USED,
    EXHAUSTED,
    EXPIRED,
    REVOKED,
    FAILED
}
