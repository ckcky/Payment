package com.payment.order.domain;

/**
 * 交易状态机（spec：待处理 → 处理中 → 成功/失败/取消/未知；未知不可直接猜成败，由权威结果收敛）。
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
}
