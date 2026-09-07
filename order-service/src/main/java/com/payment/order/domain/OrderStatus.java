package com.payment.order.domain;

/**
 * 订单状态机（spec：待确认 → 待支付 → 已支付 → 履约中 → 已完成/已取消/已关闭；不支持部分支付；
 * spec 019 / ADR-0067 补退款态：部分退款 / 已退款，终态 CLOSED 之外可由退款收敛）。
 */
public enum OrderStatus {
    PENDING_CONFIRMATION,
    PENDING_PAYMENT,
    PAID,
    FULFILLING,
    COMPLETED,
    /** 部分退款（spec 019）：已退金额 &gt; 0 且 &lt; 已支付。 */
    PARTIALLY_REFUNDED,
    /** 已退款（spec 019）：已退金额 = 已支付金额。 */
    REFUNDED,
    CANCELLED,
    CLOSED
}
