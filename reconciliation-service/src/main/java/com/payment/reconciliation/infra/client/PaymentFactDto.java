package com.payment.reconciliation.infra.client;

/**
 * 支付事实 RPC 响应 DTO（镜像 payment-service 的 PaymentFactResponse）。
 * 金额为最小货币单位（long）。
 *
 * @param merchantId 商户号（032/G2：全链不丢商户维度）
 */
public record PaymentFactDto(String paymentNo, String channelReference, long amountMinor,
                             String currencyCode, String status, String merchantId) {

    /** 兼容构造（032 前的 5 字段形态）：merchantId 缺省为 null。 */
    public PaymentFactDto(String paymentNo, String channelReference, long amountMinor,
                          String currencyCode, String status) {
        this(paymentNo, channelReference, amountMinor, currencyCode, status, null);
    }
}
