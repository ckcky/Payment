package com.payment.common.dto.rpc;

/**
 * 履约完成 RPC 的响应（entitlement-service → fulfillment-service）。
 *
 * <p>{@code status} 为 {@code EntitlementStatus} 枚举名（String）。</p>
 */
public record EntitlementGrantedResponse(Long entitlementId, String status) {
}
