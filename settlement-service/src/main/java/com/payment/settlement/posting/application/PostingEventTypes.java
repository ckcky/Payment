package com.payment.settlement.posting.application;

/**
 * 台账事件类型常量（spec 034 §9.2 / plan §2.3 矩阵，settlement 落点子集）。
 *
 * <p>记账类载荷 = AccountingEventRequest 原文（账本按 {eventType}:{sourceId}
 * 派生键吸收重复，重放不双记——031 §9）。</p>
 */
public final class PostingEventTypes {

    /** settlement → Ledger：商户结算记账（sourceId = batchNo；契约枚举 AccountingEventType.MERCHANT_SETTLEMENT）。 */
    public static final String MERCHANT_SETTLEMENT = "MERCHANT_SETTLEMENT";

    private PostingEventTypes() {
    }
}
