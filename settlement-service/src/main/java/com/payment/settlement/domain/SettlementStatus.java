package com.payment.settlement.domain;

/**
 * 结算批次状态机枚举（与 Spec 状态机一致）。
 *
 * <p>待计算 → 计算中 → 就绪 → 执行中 → 成功 / 失败 / 未知；未知仅由权威结果收敛为成功/失败，
 * 终态（成功/失败/关闭）吸收迟到冲突结果。</p>
 */
public enum SettlementStatus {
    PENDING,
    CALCULATING,
    READY,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    CLOSED
}
