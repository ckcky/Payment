package com.payment.payment.application.channel;

/**
 * 渠道交互结果：成功 / 失败 / 未知（超时、断连或不完整响应）。
 *
 * <p>只有渠道明确成功/失败才进入对应终态；{@link Status#UNKNOWN} 绝不臆断成败。</p>
 */
public record ChannelResult(Status status, String channelReference, String reason) {

    public enum Status {
        SUCCESS,
        FAILURE,
        UNKNOWN
    }

    public static ChannelResult success(String channelReference) {
        return new ChannelResult(Status.SUCCESS, channelReference, null);
    }

    public static ChannelResult failure(String channelReference, String reason) {
        return new ChannelResult(Status.FAILURE, channelReference, reason);
    }

    public static ChannelResult unknown(String reason) {
        return new ChannelResult(Status.UNKNOWN, null, reason);
    }
}
