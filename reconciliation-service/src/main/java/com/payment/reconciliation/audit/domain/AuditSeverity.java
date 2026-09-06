package com.payment.reconciliation.audit.domain;

/**
 * 审计差异严重级（FR-011）：供结算门禁与演示排序使用。
 * BLOCKER：资金错配（漏记/多记/重复）；MAJOR：科目/跨账不一致；MINOR：报表口径偏差。
 */
public enum AuditSeverity {
    BLOCKER,
    MAJOR,
    MINOR
}
