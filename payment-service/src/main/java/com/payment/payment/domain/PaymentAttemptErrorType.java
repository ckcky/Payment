package com.payment.payment.domain;

/**
 * 渠道尝试的错误分类（spec US3 / FR-005~FR-007 / ADR-0012）：本次失败属于哪一类。
 *
 * <p><b>本枚举不参与重试判定</b>——重试只看通信响应码 {@code TransportCode}
 * （非 {@code SUCCESS} 即重试，见 {@code ChannelResult#retryable()}）。本枚举用于落库观测与排障，
 * 由双响应码派生（见 {@code ChannelResult#errorType()}）。</p>
 */
public enum PaymentAttemptErrorType {
    /**
     * 通信失败：通信响应码非 {@code SUCCESS}（超时 / 断连 / 5xx / 协议错误）。
     * 幂等可重试；重试耗尽后仍记此值，支付进 UNKNOWN（FR-007）。
     */
    TRANSIENT,
    /** 硬拒绝：通信成功但业务明确拒绝（余额不足、风控拒绝等），不可重试，直接进失败。 */
    HARD,
    /** 结果不明确：通信成功但下游未给出业务结论，不重试，进 UNKNOWN 等待查询/对账收敛。 */
    UNKNOWN
}
