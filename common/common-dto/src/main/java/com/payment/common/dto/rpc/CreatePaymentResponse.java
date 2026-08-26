package com.payment.common.dto.rpc;

/**
 * 创建支付意图的跨服务 RPC 响应（payment-service → order-service）。
 *
 * <p>{@code status} 为 {@code PaymentStatus} 枚举名（String），避免调用方与领域枚举耦合。</p>
 */
public record CreatePaymentResponse(Long paymentId, String status) {
}
