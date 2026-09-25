package com.payment.channelgateway.application;

/**
 * 渠道退款请求（平台 → 渠道）。渠道实现只读取必要字段，不访问支付聚合内部状态。
 *
 * <p>ADR-0063：退款标识用业务单号 {@code refundNo}，不用数值主键。</p>
 *
 * <h3>spec 030 契约扩展（FR-106）</h3>
 * 由 5 字段扩为 9 字段。<b>真实渠道退款 MUST 带渠道交易号</b>（{@code channelTransactionId}）——
 * 退款是对原支付的操作，渠道侧靠原交易号定位原单，缺了就无法受理。
 * <b>保留 5 参兼容构造器</b>。
 */
public record RefundRequest(String paymentNo, String refundNo, long amountMinor,
                            String currencyCode, String channelCode,
                            String channelTransactionId, String outRequestNo,
                            String reason, String refundNotifyUrl) {

    /**
     * 兼容构造器（spec 030 前的既有形态）：仅必要字段，扩展字段一律 {@code null}。
     *
     * <p>⚠️ 真实渠道场景<b>不应</b>走此构造——缺 {@code channelTransactionId} 会导致
     * 渠道无法定位原交易。此处保留只为既有调用点零改动编译（SC-A-02）。</p>
     */
    public RefundRequest(String paymentNo, String refundNo, long amountMinor,
                         String currencyCode, String channelCode) {
        this(paymentNo, refundNo, amountMinor, currencyCode, channelCode,
                null, null, null, null);
    }
}
