package com.payment.common.dto.channel;

/**
 * 渠道退款指令（Payment → 渠道网关，spec 037 / FR-004）。
 *
 * <p>与 {@code RefundRequest} 同形，但把跨域标识换成网关业务单号 {@code channelNo}（FR-001），
 * 并落到 {@code common-dto}（FR-003 / INV-3）。</p>
 *
 * <p><b>真实渠道退款 MUST 带渠道交易号</b>（{@code channelTransactionId}）：退款是对原支付的操作，
 * 渠道侧靠原交易号定位原单，缺了就无法受理。该值即
 * {@code payment_attempts.channel_reference}，<b>不是</b>平台侧交易号（{@code TX}+雪花）——
 * 混用即 C-12 / S21 事故（ADR-0063）。</p>
 *
 * <p><b>不得出现数值主键</b>（{@code Long} 分量）：由
 * {@code ChannelContractTest#outboundContractsCarryNoNumericPrimaryKey} 钉死。</p>
 *
 * @param channelNo            渠道网关业务单号（{@code CH} + 雪花），<b>必填</b>
 * @param paymentNo            原支付单号（{@code PM} + 雪花）
 * @param refundNo             退款单号（{@code PMRF}/{@code TXRF} + 雪花）
 * @param amountMinor          退款金额（最小货币单位；ADR-0016：恒全退）
 * @param currencyCode         币种（ISO-4217 三字母）
 * @param channelCode          渠道编号（精确寻址，禁止重新路由：INV-6）
 * @param channelTransactionId 渠道交易号（定位原交易，必填于真实渠道）
 * @param outRequestNo         渠道侧退款请求号（部分渠道要求全局唯一）
 * @param reason               退款原因
 * @param refundNotifyUrl      退款结果异步通知地址
 */
public record ChannelRefundCommand(String channelNo, String paymentNo, String refundNo,
                                   long amountMinor, String currencyCode, String channelCode,
                                   String channelTransactionId, String outRequestNo,
                                   String reason, String refundNotifyUrl) {

    public ChannelRefundCommand {
        requireText(channelNo, "channelNo");
        requireText(paymentNo, "paymentNo");
        requireText(refundNo, "refundNo");
        requireText(channelCode, "channelCode");
    }

    /** 极简形态：只给定位与金额，扩展字段一律 {@code null}。 */
    public static ChannelRefundCommand of(String channelNo, String paymentNo, String refundNo,
                                          long amountMinor, String currencyCode, String channelCode) {
        return new ChannelRefundCommand(channelNo, paymentNo, refundNo, amountMinor,
                currencyCode, channelCode, null, null, null, null);
    }

    /** 跨域标识 MUST 为业务单号且非空（INV-4 / ADR-0063）。 */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("channel contract field must be non-blank: " + field);
        }
    }
}
