package com.payment.common.dto.channel;

/**
 * 渠道支付状态快照（渠道网关 → Payment，spec 037 / FR-004 / FR-005）。
 *
 * <p>主动查询的返回物：把「渠道侧现在到底是什么状态」翻译成跨域口径。
 * 以 {@code channelNo} 回指被查询的网关单，消费端据此把 UNKNOWN 收敛为权威终态。</p>
 *
 * <h3>为什么金额用包装类型 {@code Long} 而不是 {@code long}</h3>
 * <p>{@code null} 表示<b>「报文里没有金额信息」</b>，与「金额为 0」语义完全不同——
 * 把「没读到」当成「0 元」会制造出一批假差异（该纪律沿用
 * {@code ParsedCallback.NotifiedAmount#UNKNOWN} 的既有口径）。</p>
 *
 * @param status               渠道侧支付结论（三档，含 UNKNOWN）
 * @param channelNo            被查询的渠道网关业务单号（FR-005：以 channelNo 为主键）
 * @param channelTransactionId 渠道交易号（未读到时为空）
 * @param amountMinor          渠道声称的金额（最小货币单位；{@code null} = 未读到）
 * @param currencyCode         渠道声称的币种（{@code null} = 未读到）
 * @param reason               失败/无结论原因（成功时为 {@code null}）
 */
public record ChannelQuerySnapshot(ChannelPayStatus status, String channelNo,
                                   String channelTransactionId, Long amountMinor,
                                   String currencyCode, String reason) {

    public ChannelQuerySnapshot {
        if (status == null) {
            throw new IllegalArgumentException("query snapshot must carry a status");
        }
        if (channelNo == null || channelNo.isBlank()) {
            throw new IllegalArgumentException("query snapshot must be keyed by channelNo (FR-005)");
        }
    }

    /** 金额/币种是否读到（两者都有才算数，否则 MUST NOT 参与金额校验）。 */
    public boolean hasAmount() {
        return amountMinor != null && currencyCode != null && !currencyCode.isBlank();
    }

    /** 无结论快照（查询也没问出来，保持 UNKNOWN）。 */
    public static ChannelQuerySnapshot unknown(String channelNo, String reason) {
        return new ChannelQuerySnapshot(ChannelPayStatus.UNKNOWN, channelNo, null, null, null, reason);
    }
}
