package com.payment.common.dto.rpc;

/**
 * 查询支付金额的跨服务 RPC 响应（payment-service → refund-service）。
 *
 * <p>{@code status} 为 {@code PaymentStatus} 枚举名（String）；{@code paidAmountMinor} 为
 * 该支付已确认的成功金额（最小货币单位）。仅返回退款资格判断所需事实。</p>
 */
public record PaymentAmountQueryResponse(String paymentNo, String orderNo, String userId,
                                         long paidAmountMinor, String currencyCode, String status) {
}
