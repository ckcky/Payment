package com.payment.payment.application.channel;

/**
 * 渠道退款请求（平台 → 渠道）。渠道实现只读取必要字段，不访问支付聚合内部状态。
 */
public record RefundRequest(Long paymentId, Long refundId, long amountMinor,
                            String currencyCode, String channelCode) {
}
