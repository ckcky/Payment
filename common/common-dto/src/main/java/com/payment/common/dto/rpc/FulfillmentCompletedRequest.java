package com.payment.common.dto.rpc;

/**
 * 履约完成的跨服务 RPC 请求（fulfillment-service → entitlement-service）。
 *
 * <p>履约完成后触发权益授予；支付成功不等于权益已发放（Constitution 领域边界 #3）。</p>
 */
public record FulfillmentCompletedRequest(Long fulfillmentId, String orderId, String userId) {
}
