package com.payment.order.domain;

/**
 * 交易层退款单状态机（spec 019 / ADR-0067）：
 * REQUESTED → PROCESSING → { SUCCEEDED | FAILED | REJECTED }。
 *
 * <p>终态吸收：终态收到相反结果不回退（冲突交对账兜底）。REJECTED 为
 * payment 侧 RefundPolicy 权威判定拒绝（非 SUCCEEDED 支付单退款等），属终态。</p>
 */
public enum RefundOrderStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    REJECTED;

    /** 是否终态（终态吸收，不再迁移）。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED;
    }
}
