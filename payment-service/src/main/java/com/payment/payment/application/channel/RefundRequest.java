package com.payment.payment.application.channel;

/**
 * 渠道退款请求（平台 → 渠道）。渠道实现只读取必要字段，不访问支付聚合内部状态。
 *
 * <p>ADR-0063：退款标识用业务单号 {@code refundNo}，不用数值主键。</p>
 */
public record RefundRequest(String paymentNo, String refundNo, long amountMinor,
                            String currencyCode, String channelCode) {
}
