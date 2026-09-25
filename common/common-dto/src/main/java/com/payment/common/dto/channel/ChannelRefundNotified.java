package com.payment.common.dto.channel;

import java.time.Instant;

/**
 * 渠道退款结果通知（渠道网关 → Payment，spec 037 / FR-004 / FR-006 / SC-004）。
 *
 * <p>本契约取代 {@code RefundResultListener#onChannelRefundResult(String, ChannelResult)}——
 * 修正<b>接口定义权方向</b>（FR-012 / INV-2）：旧形态下接口定义在渠道包、实现却在退款应用服务，
 * 形成「渠道 → 退款」的反向驱动；现在由 Payment 定义 {@code PaymentNotifyPort}
 * 并实现，渠道网关只依赖接口。</p>
 *
 * <p>与 {@link ChannelPayNotified} 同构，但按 {@code refundNo} 寻址——退款的收敛编排
 * （{@code RefundResultProcessor}）以退款单号为入口。</p>
 *
 * <h3>FR-006：MUST 携带 {@code channelCode}</h3>
 *
 * @param channelNo            渠道网关业务单号（{@code CH} + 雪花，FR-001），<b>必填</b>
 * @param refundNo             退款单号（{@code PMRF}/{@code TXRF} + 雪花），事件寻址键
 * @param channelCode          渠道编号（FR-006，<b>必填</b>）
 * @param status               渠道侧退款结论（三档，含 UNKNOWN）
 * @param channelTransactionId 渠道退款交易号（未读到时为空）
 * @param amountMinor          渠道声称的退款金额（{@code null} = 未读到，跳过校验）
 * @param currencyCode         渠道声称的币种（{@code null} = 未读到）
 * @param reason               失败/无结论原因（成功时为 {@code null}）
 * @param occurredAt           渠道侧事件发生时刻（用于乱序通知的时序判定）
 */
public record ChannelRefundNotified(String channelNo, String refundNo, String channelCode,
                                    ChannelRefundStatus status, String channelTransactionId,
                                    Long amountMinor, String currencyCode, String reason,
                                    Instant occurredAt) {

    public ChannelRefundNotified {
        requireText(channelNo, "channelNo");
        requireText(refundNo, "refundNo");
        requireText(channelCode, "channelCode");
        if (status == null) {
            throw new IllegalArgumentException("refund notification must carry a status");
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
