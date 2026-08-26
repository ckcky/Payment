package com.payment.order.domain;

/**
 * 订单状态机（spec：待确认 → 待支付 → 部分支付/已支付 → 履约中 → 已完成/已取消/已关闭）。
 */
public enum OrderStatus {
    PENDING_CONFIRMATION,
    PENDING_PAYMENT,
    PARTIALLY_PAID,
    PAID,
    FULFILLING,
    COMPLETED,
    CANCELLED,
    CLOSED
}
