package com.payment.payment.application.channel;

/**
 * 主动查询请求：携带平台侧标识，供渠道按自身引用/交易号定位状态（spec US2 / ADR-0003）。
 */
public record QueryStatusRequest(Long paymentId, String transactionId, String idempotencyKey) {
}
