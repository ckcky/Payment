package com.payment.common.dto.rpc;

/**
 * 支付成功的跨服务 RPC 请求（payment-service → fulfillment-service / order-service）。
 *
 * <p>只携带下游所需的原始事实，不暴露 payment 模块内部实体。对 fulfillment 仅「触发」履约、
 * 不直接决定履约结果（Constitution 领域边界 #3/#6）；对 order 驱动订单/交易状态回写（幂等吸收）。</p>
 */
public record PaymentSucceededRequest(Long paymentId, String orderId, String transactionId,
                                      String userId, long amountMinor, String currencyCode) {
}
