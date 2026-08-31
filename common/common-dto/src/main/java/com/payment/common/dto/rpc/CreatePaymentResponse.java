package com.payment.common.dto.rpc;

/**
 * 创建支付意图的跨服务 RPC 响应（payment-service → order-service）。
 *
 * <p>{@code status} 为 {@code PaymentStatus} 枚举名（String），避免调用方与领域枚举耦合。</p>
 *
 * <p>{@code payUrl}（ADR-0048 修订版，2026-08-31 裁决）：mock 收银台跳转链接，
 * 仅当 {@code payment.mock-cashier.enabled=true} 时返回，否则为 {@code null}
 * （既有"同步 charge"主链不受影响）。order-service 将其原样透传进
 * {@code CreateOrderResponse}，由演示控制台 {@code window.open} 打开。</p>
 */
public record CreatePaymentResponse(Long paymentId, String status, String payUrl) {

    /** 兼容构造（无收银台路径）：payUrl 为 null。 */
    public CreatePaymentResponse(Long paymentId, String status) {
        this(paymentId, status, null);
    }
}
