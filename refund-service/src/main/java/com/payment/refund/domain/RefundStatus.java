package com.payment.refund.domain;

/**
 * 退款状态机枚举（与 Spec 状态机一致）。
 *
 * <p>申请中 → 处理中 → 成功 / 部分成功 / 失败 / 未知；申请中可被拒绝；
 * 成功 / 部分成功 / 失败 / 拒绝 可关闭。UNKNOWN 是待收敛状态，不是失败别名。</p>
 */
public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    UNKNOWN,
    REJECTED,
    CLOSED
}
