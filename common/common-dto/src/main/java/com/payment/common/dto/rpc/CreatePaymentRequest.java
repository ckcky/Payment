package com.payment.common.dto.rpc;

/**
 * 创建支付意图的跨服务 RPC 请求（order-service → payment-service）。
 *
 * <p>金额为最小货币单位（long）。幂等键由调用方提供，payment-service 据此保证重复请求不产生
 * 第二次资金动作。</p>
 */
public record CreatePaymentRequest(String orderId, String transactionId, String userId,
                                   long amountMinor, String currencyCode, String idempotencyKey,
                                   String channelCode) {
}
