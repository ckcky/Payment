package com.payment.reconciliation.domain;

/**
 * 对账批次状态机（与 Spec 状态机一致）。
 *
 * <p>PENDING → RECONCILING → CONSISTENT（无差异）/ HAS_DIFFERENCE（有差异）；
 * HAS_DIFFERENCE → PROCESSING → CLOSED；CONSISTENT → CLOSED。
 * 状态迁移集中在 {@link ReconciliationBatch}，禁止散落 set。</p>
 */
public enum ReconciliationStatus {
    PENDING,
    RECONCILING,
    CONSISTENT,
    HAS_DIFFERENCE,
    PROCESSING,
    CLOSED
}
