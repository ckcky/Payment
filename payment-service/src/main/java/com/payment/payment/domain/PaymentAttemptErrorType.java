package com.payment.payment.domain;

/**
 * 渠道尝试的错误分类（spec US3 / FR-005~FR-007 / ADR-0012）：决定本次失败是否可重试。
 *
 * <p>只有 {@link #TRANSIENT}（瞬时错误、调用幂等）才会被有限重试；{@link #HARD} 是明确的
 * 硬拒绝（如余额不足、卡被拒），不重试；{@link #UNKNOWN} 表示渠道响应不明确，不重试，
 * 直接进 UNKNOWN 由主动查询收敛（Constitution §V：不把「未确认」当成功或失败）。</p>
 */
public enum PaymentAttemptErrorType {
    /** 瞬时错误（网络超时、渠道临时不可用）：幂等可重试。 */
    TRANSIENT,
    /** 硬拒绝：明确不可重试，直接进失败。 */
    HARD,
    /** 渠道响应不明确：不重试，进 UNKNOWN 等待查询/对账收敛。 */
    UNKNOWN
}
