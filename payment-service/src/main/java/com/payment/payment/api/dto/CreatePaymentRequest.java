package com.payment.payment.api.dto;

import com.payment.payment.application.CreatePaymentCommand;

/**
 * 创建支付意图请求。金额为最小货币单位（long）。
 */
public record CreatePaymentRequest(String transactionId, String orderId, String userId,
                                   long amountMinor, String currencyCode, String idempotencyKey,
                                   String channelCode) {

    public CreatePaymentCommand toCommand() {
        return new CreatePaymentCommand(transactionId, orderId, userId, amountMinor, currencyCode,
                idempotencyKey, channelCode);
    }
}
