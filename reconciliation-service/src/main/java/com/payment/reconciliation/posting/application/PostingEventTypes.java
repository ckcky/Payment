package com.payment.reconciliation.posting.application;

/**
 * 台账事件类型常量（spec 034 §9.2 / plan §2.3 矩阵，reconciliation 落点子集）。
 *
 * <p>记账类载荷 = AccountingEventRequest 原文（账本按 {eventType}:{sourceId}
 * 派生键吸收重复，重放不双记——031 §9）。</p>
 */
public final class PostingEventTypes {

    /** reconciliation → Ledger：挂账/调账（sourceId = adjustNo，契约枚举 AccountingEventType.ADJUSTMENT）。 */
    public static final String ADJUSTMENT = "ADJUSTMENT";

    private PostingEventTypes() {
    }
}
