package com.payment.payment.application.channel;

/**
 * 主动查询请求：携带平台侧标识，供渠道按自身引用/交易号定位状态（spec US2 / ADR-0003）。
 *
 * <p>跨系统标识一律业务单号（ADR-0063）：{@code paymentNo}（PM+雪花），禁止数值 paymentId。</p>
 */
public record QueryStatusRequest(String paymentNo, String transactionId, String idempotencyKey) {
}
