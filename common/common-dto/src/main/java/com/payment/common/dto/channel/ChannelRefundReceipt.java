package com.payment.common.dto.channel;

/**
 * 渠道退款回执（渠道网关 → Payment，spec 037 / FR-004）。
 *
 * <p>ADR-0016：退款恒全退，<b>不携带「实际退款金额」</b>——曾短暂存在的部分退款档已否决，
 * 带上金额字段只会诱发消费端按部分退款处理。</p>
 *
 * @param status               渠道侧退款结论（三档，含 UNKNOWN）
 * @param channelTransactionId 渠道退款交易号（无结论时可空）
 * @param reason               失败/无结论原因（成功时为 {@code null}）
 */
public record ChannelRefundReceipt(ChannelRefundStatus status, String channelTransactionId,
                                   String reason) {

    public ChannelRefundReceipt {
        if (status == null) {
            throw new IllegalArgumentException("refund receipt must carry a status");
        }
    }

    /** 成功回执。 */
    public static ChannelRefundReceipt success(String channelTransactionId) {
        return new ChannelRefundReceipt(ChannelRefundStatus.SUCCESS, channelTransactionId, null);
    }
}
