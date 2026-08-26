package com.payment.payment.domain;

/**
 * 支付尝试状态机（data-model：待处理 → 已受理 → 成功/失败/未知）。
 *
 * <p>未知尝试在获得权威结果前保持未收敛。</p>
 */
public enum PaymentAttemptStatus {
    PENDING,
    ACCEPTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
