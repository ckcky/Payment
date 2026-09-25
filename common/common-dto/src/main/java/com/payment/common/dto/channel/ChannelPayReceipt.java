package com.payment.common.dto.channel;

/**
 * 渠道扣款回执（渠道网关 → Payment，spec 037 / FR-004）。
 *
 * <p>网关把渠道响应翻译成本回执：Payment 只看到 {@link ChannelPayStatus} 三档结论与
 * 渠道交易号，看不到任何渠道私有类型或状态码（SC-004）。</p>
 *
 * <h3>为什么凭证随回执返回</h3>
 * <p>真实渠道下单成功后不会立刻收到钱，而是返回一个让买家完成付款的
 * {@link PayCredential}（跳转 URL / 表单 HTML / 二维码串 / JSAPI 参数 / client_secret）。
 * 该凭证由网关域内的插件产出，Payment 只负责透传给前端——<b>MUST NOT 入库</b>
 * （见 {@link PayCredential} 的安全约束）。</p>
 *
 * <p>{@code status == UNKNOWN} 时 {@code channelTransactionId} 可能为空：
 * 超时/断连时渠道交易号未读到是正常形态，Payment <b>MUST NOT</b> 据此臆断成败，
 * 只能走主动查询收敛（Constitution §V.7）。</p>
 *
 * @param status                渠道侧支付结论（三档，含 UNKNOWN）
 * @param channelTransactionId  渠道交易号（写回 {@code payment_attempts.channel_reference}；UNKNOWN 时可空）
 * @param reason                失败/无结论原因（成功时为 {@code null}）
 * @param credential            付款凭证（无凭证时为 {@code null}）
 */
public record ChannelPayReceipt(ChannelPayStatus status, String channelTransactionId,
                                String reason, PayCredential credential) {

    public ChannelPayReceipt {
        if (status == null) {
            throw new IllegalArgumentException("pay receipt must carry a status");
        }
    }

    /** 无凭证回执（mock / 主动查询等场景）。 */
    public static ChannelPayReceipt of(ChannelPayStatus status, String channelTransactionId, String reason) {
        return new ChannelPayReceipt(status, channelTransactionId, reason, null);
    }

    /** 是否携带可交付前端的付款凭证。 */
    public boolean hasCredential() {
        return credential != null;
    }
}
