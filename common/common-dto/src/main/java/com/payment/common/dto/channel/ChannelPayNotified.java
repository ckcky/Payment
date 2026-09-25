package com.payment.common.dto.channel;

import java.time.Instant;

/**
 * 渠道支付结果通知（渠道网关 → Payment，spec 037 / FR-004 / FR-006 / SC-004）。
 *
 * <p>这是<b>入向</b>跨域事件：渠道网关收完 HTTP 回调、验完签、翻译完报文之后，
 * 只把本结构交给 Payment 定义的 {@code PaymentNotifyPort}（FR-010 / INV-2）。
 * 渠道私有类型（渠道报文结构、渠道状态码、渠道 SDK 异常）<b>一律不出现在本 record 里</b>——
 * 这是 SC-004「Payment 收到的事件不含任何渠道私有类型」的落点。</p>
 *
 * <h3>FR-006：MUST 携带 {@code channelCode}</h3>
 * <p>没有渠道编号，Payment 无法把事件与「哪一次渠道交互」对齐，也无法做渠道维度的对账与排障。</p>
 *
 * <h3>金额为包装类型 {@code Long}</h3>
 * <p>{@code null} = 渠道报文未携带金额，<b>不等于</b> 0 元。Payment 据此<b>跳过</b>金额校验
 * 而不是放行一个 {@code 0}（沿用 {@code ParsedCallback.NotifiedAmount} 的既有纪律）。</p>
 *
 * @param channelNo            渠道网关业务单号（{@code CH} + 雪花，FR-001），<b>必填</b>
 * @param paymentNo            平台支付单号（{@code PM} + 雪花），事件寻址键
 * @param channelCode          渠道编号（FR-006，<b>必填</b>）
 * @param status               渠道侧支付结论（三档，含 UNKNOWN）
 * @param channelTransactionId 渠道交易号（未读到时为空）
 * @param amountMinor          渠道声称的金额（{@code null} = 未读到，跳过校验）
 * @param currencyCode         渠道声称的币种（{@code null} = 未读到）
 * @param reason               失败/无结论原因（成功时为 {@code null}）
 * @param occurredAt           渠道侧事件发生时刻（用于乱序通知的时序判定）
 */
public record ChannelPayNotified(String channelNo, String paymentNo, String channelCode,
                                 ChannelPayStatus status, String channelTransactionId,
                                 Long amountMinor, String currencyCode, String reason,
                                 Instant occurredAt) {

    public ChannelPayNotified {
        requireText(channelNo, "channelNo");
        requireText(paymentNo, "paymentNo");
        requireText(channelCode, "channelCode");
        if (status == null) {
            throw new IllegalArgumentException("pay notification must carry a status");
        }
    }

    /** 金额/币种是否读到（两者都有才算数）。 */
    public boolean hasAmount() {
        return amountMinor != null && currencyCode != null && !currencyCode.isBlank();
    }

    /** 跨域标识 MUST 为业务单号且非空（INV-4 / ADR-0063）。 */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("channel contract field must be non-blank: " + field);
        }
    }
}
