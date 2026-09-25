package com.payment.common.dto.channel;

/**
 * 渠道主动查询指令（Payment → 渠道网关，spec 037 / FR-004 / FR-005）。
 *
 * <h3>FR-005：本契约 MUST 以 {@code channelNo} 为主键</h3>
 * <p>{@code channelTransactionId}（渠道流水号）<b>仅作渠道侧辅助定位</b>；
 * <b>禁止</b>以 {@code outTradeNo}（渠道侧的商户订单号）作为查单主键——它是渠道私有口径，
 * 一旦成为主键，Payment 就得知道渠道怎么命名订单，网关边界随即失效。</p>
 *
 * <p>首个 record 分量必须是 {@code channelNo}——由
 * {@code ChannelContractTest#queryCommandIsKeyedByChannelNo} 钉死。</p>
 *
 * <p><b>不得出现数值主键</b>（{@code Long} 分量）：由
 * {@code ChannelContractTest#outboundContractsCarryNoNumericPrimaryKey} 钉死。</p>
 *
 * @param channelNo            渠道网关业务单号（{@code CH} + 雪花），<b>查单主键</b>
 * @param paymentNo            平台支付单号（{@code PM} + 雪花）
 * @param channelCode          渠道编号（精确寻址，禁止重新路由：INV-6）
 * @param transactionId        平台侧交易号（{@code TX} + 雪花）——<b>不是</b>渠道交易号
 * @param idempotencyKey       幂等键（同一笔查询重放不产生副作用）
 * @param channelTransactionId 渠道交易号（{@code payment_attempts.channel_reference}，辅助定位）
 */
public record ChannelQueryCommand(String channelNo, String paymentNo, String channelCode,
                                  String transactionId, String idempotencyKey,
                                  String channelTransactionId) {

    public ChannelQueryCommand {
        requireText(channelNo, "channelNo");
        requireText(paymentNo, "paymentNo");
        requireText(channelCode, "channelCode");
    }

    /** 极简形态：只给主键与路由。 */
    public static ChannelQueryCommand of(String channelNo, String paymentNo, String channelCode) {
        return new ChannelQueryCommand(channelNo, paymentNo, channelCode, null, null, null);
    }

    /** 跨域标识 MUST 为业务单号且非空（INV-4 / ADR-0063）。 */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("channel contract field must be non-blank: " + field);
        }
    }
}
