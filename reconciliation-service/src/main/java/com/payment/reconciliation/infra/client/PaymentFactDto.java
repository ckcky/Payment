package com.payment.reconciliation.infra.client;

/**
 * 支付事实 RPC 响应 DTO（镜像 payment-service 的 PaymentFactResponse）。
 * 金额为最小货币单位（long）。
 */
public record PaymentFactDto(String paymentNo, String channelReference, long amountMinor,
                             String currencyCode, String status) {
}
