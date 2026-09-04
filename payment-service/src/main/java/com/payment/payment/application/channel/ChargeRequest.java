package com.payment.payment.application.channel;

/**
 * 渠道扣款请求（平台 → 渠道）。渠道实现只读取必要字段，不访问支付聚合内部状态。
 *
 * <p>跨系统标识一律业务单号（ADR-0063）：{@code paymentNo}（PM+雪花），禁止数值 paymentId。</p>
 */
public record ChargeRequest(String paymentNo, Long attemptId, long amountMinor,
                            String currencyCode, String channelCode) {
}
