package com.payment.common.dto.rpc;

/**
 * 退款 → 履约撤销的响应（fulfillment-service → payment-service（refund 包））。
 *
 * <p>{@code status} 为撤销结果枚举名：{@code CANCELLED}（PENDING 已取消）、
 * {@code SKIPPED}（非 PENDING/已交付，跳过但非错误）、{@code REJECTED}（请求非法）。</p>
 */
public record RefundFulfillmentResponse(String refundNo, String status) {
}
