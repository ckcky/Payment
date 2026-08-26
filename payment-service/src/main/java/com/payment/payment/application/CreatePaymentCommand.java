package com.payment.payment.application;

/**
 * 创建支付意图命令（应用层输入，独立于 API DTO）。
 */
public record CreatePaymentCommand(String transactionId, String orderId, String userId,
                                   long amountMinor, String currencyCode, String idempotencyKey,
                                   String channelCode) {
}
