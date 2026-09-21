package com.payment.common.dto.rpc;

/**
 * 查询支付金额的跨服务 RPC 响应（payment-service → refund-service）。
 *
 * <p>{@code status} 为 {@code PaymentStatus} 枚举名（String）；{@code paidAmountMinor} 为
 * 该支付已确认的成功金额（最小货币单位）。仅返回退款资格判断所需事实。</p>
 *
 * <p>spec 031 §9（退款事件化）：补 {@code merchantId}（payments 新列）与
 * {@code channelCode}（生效支付 attempt 的渠道，INV-6 同源）——REFUND 记账事件的必填槽位；
 * 未成功/无生效 attempt 时为 null（查询侧不因缺事实而抛）。</p>
 */
public record PaymentAmountQueryResponse(String paymentNo, String orderNo, String userId,
                                         long paidAmountMinor, String currencyCode, String status,
                                         String merchantId, String channelCode) {
}
