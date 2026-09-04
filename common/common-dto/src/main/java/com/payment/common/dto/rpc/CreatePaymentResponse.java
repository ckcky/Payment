package com.payment.common.dto.rpc;

/**
 * 创建支付意图的跨服务 RPC 响应（payment-service → order-service）。
 *
 * <p>{@code status} 为 {@code PaymentStatus} 枚举名（String），避免调用方与领域枚举耦合。</p>
 *
 * <p>{@code payUrl}（ADR-0048 修订版）：mock 收银台跳转链接，仅当
 * {@code payment.mock-cashier.enabled=true} 时返回，否则为 {@code null}。</p>
 *
 * <p>{@code attemptSeq} / {@code channelCode}（Feature 015）：一交易多支付单时，
 * 标识本次尝试序号与所选渠道，供订单侧「换渠道重付」展示与对账聚合使用。</p>
 */
public record CreatePaymentResponse(String paymentNo, String status, String payUrl, int attemptSeq, String channelCode) {

    /** 兼容构造（仅支付单号 + 状态）。 */
    public CreatePaymentResponse(String paymentNo, String status) {
        this(paymentNo, status, null);
    }

    /** 兼容构造（无收银台路径）：payUrl 为 null。 */
    public CreatePaymentResponse(String paymentNo, String status, String payUrl) {
        this(paymentNo, status, payUrl, 0, null);
    }
}
