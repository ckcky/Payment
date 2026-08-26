package com.payment.common.dto.rpc;

/**
 * 支付成功 RPC 的受理响应（fulfillment-service → payment-service）。
 *
 * <p>{@code status} 为 {@code FulfillmentStatus} 枚举名（String）。履约是否受理成功由该状态表达；
 * payment-service 不回写履约结果到支付事实。</p>
 */
public record FulfillmentAcceptedResponse(Long fulfillmentId, String status) {
}
