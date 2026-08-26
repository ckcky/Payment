package com.payment.payment.application.channel;

/**
 * 渠道扣款请求（平台 → 渠道）。渠道实现只读取必要字段，不访问支付聚合内部状态。
 */
public record ChargeRequest(Long paymentId, Long attemptId, long amountMinor,
                            String currencyCode, String channelCode) {
}
