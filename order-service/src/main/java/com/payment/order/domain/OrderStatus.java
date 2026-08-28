package com.payment.order.domain;

/**
 * 订单状态机（spec：待确认 → 待支付 → 已支付 → 履约中 → 已完成/已取消/已关闭；不支持部分支付）。
 */
public enum OrderStatus {
    PENDING_CONFIRMATION,
    PENDING_PAYMENT,
    PAID,
    FULFILLING,
    COMPLETED,
    CANCELLED,
    CLOSED
}
