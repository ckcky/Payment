package com.payment.payment.domain;

/**
 * 支付状态机（data-model：待支付 → 处理中 → 成功/失败/未知 → 已关闭）。
 *
 * <p>UNKNOWN 是「结果待定」的独立状态，绝不当成成功或失败（Constitution §3）。</p>
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    CLOSED
}
