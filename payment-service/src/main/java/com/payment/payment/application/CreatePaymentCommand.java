package com.payment.payment.application;

/**
 * 创建支付意图命令（应用层输入，独立于 API DTO）。
 */
public record CreatePaymentCommand(String transactionId, String orderNo, String userId,
                                   long amountMinor, String currencyCode, String idempotencyKey,
                                   String channelCode,
                                   /** spec 031 / §13：订单携带的商户号（PAYMENT_CAPTURE 事实锚，可为 null=历史口径）。 */
                                   String merchantId) {
}
