package com.payment.posting.application;

/**
 * 台账事件类型常量（spec 034 §9.2 / plan §2.3 矩阵）。
 *
 * <p>记账类四事件载荷 = AccountingEventRequest 原文（账本按 {eventType}:{sourceId}
 * 派生键吸收重复，重放不双记——031 §9）；通知类两事件载荷 = 原 RPC 请求体原文
 * （order 侧终态吸收 / 幂等，重放 = 原请求重发）。</p>
 */
public final class PostingEventTypes {

    /** payment → Ledger：支付成功记账（sourceId = paymentNo）。 */
    public static final String PAYMENT_CAPTURE = "PAYMENT_CAPTURE";

    /** payment → Ledger：退款冲正记账（sourceId = refundNo）。 */
    public static final String REFUND = "REFUND";

    /** settlement → Ledger：商户结算记账（sourceId = batchNo）。 */
    public static final String SETTLEMENT_MERCHANT = "SETTLEMENT_MERCHANT";

    /** reconciliation → Ledger：挂账/调账（sourceId = adjustNo）。 */
    public static final String ADJUSTMENT = "ADJUSTMENT";

    /** payment → order：支付成功通知（sourceId = paymentNo，M7 泛化）。 */
    public static final String ORDER_NOTIFY_SUCCEEDED = "ORDER_NOTIFY_SUCCEEDED";

    /** payment → order：退款结果通知（sourceId = refundNo，M7 泛化）。 */
    public static final String ORDER_NOTIFY_REFUND_RESULT = "ORDER_NOTIFY_REFUND_RESULT";

    private PostingEventTypes() {
    }
}
