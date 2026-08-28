package com.payment.payment.application.channel;

import com.payment.payment.domain.PaymentAttemptErrorType;

/**
 * 渠道交互结果：成功 / 失败 / 未知（超时、断连或不完整响应）。
 *
 * <p>只有渠道明确成功/失败才进入对应终态；{@link Status#UNKNOWN} 绝不臆断成败。</p>
 *
 * <p>{@code errorType} 描述失败的可重试性（spec US3 / ADR-0012）：{@link PaymentAttemptErrorType#TRANSIENT}
 * 表示幂等可重试的瞬时错误；{@link PaymentAttemptErrorType#HARD} 表示硬拒绝不重试；
 * {@link PaymentAttemptErrorType#UNKNOWN} 表示结果不明确，不重试。</p>
 */
public record ChannelResult(Status status, String channelReference, String reason,
                            PaymentAttemptErrorType errorType) {

    public enum Status {
        SUCCESS,
        FAILURE,
        UNKNOWN
    }

    public static ChannelResult success(String channelReference) {
        return new ChannelResult(Status.SUCCESS, channelReference, null, null);
    }

    /** 硬拒绝：明确不可重试（FR-006）。 */
    public static ChannelResult failure(String channelReference, String reason) {
        return new ChannelResult(Status.FAILURE, channelReference, reason, PaymentAttemptErrorType.HARD);
    }

    /** 瞬时错误：幂等可重试（FR-005）。 */
    public static ChannelResult transientFailure(String reason) {
        return new ChannelResult(Status.FAILURE, null, reason, PaymentAttemptErrorType.TRANSIENT);
    }

    /** 渠道响应不明确：不重试，进 UNKNOWN 由查询收敛（ADR-0012）。 */
    public static ChannelResult unknown(String reason) {
        return new ChannelResult(Status.UNKNOWN, null, reason, PaymentAttemptErrorType.UNKNOWN);
    }
}
