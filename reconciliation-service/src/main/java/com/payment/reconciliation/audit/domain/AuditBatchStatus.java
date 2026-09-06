package com.payment.reconciliation.audit.domain;

/**
 * 审计批次状态机（spec 017 plan §7.3）：
 * PROCESSING → BALANCED / HAS_DIFFERENCE → RECHECKING → (BALANCED | HAS_DIFFERENCE) → CLOSED。
 * CLOSED 为只读终态；未收口差异时关批被门禁拒绝（FR-018）。
 */
public enum AuditBatchStatus {
    PROCESSING,
    BALANCED,
    HAS_DIFFERENCE,
    RECHECKING,
    CLOSED
}
