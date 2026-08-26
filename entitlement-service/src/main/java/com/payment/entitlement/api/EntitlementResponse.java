package com.payment.entitlement.api;

import com.payment.entitlement.domain.Entitlement;

/**
 * 权益查询响应 DTO。
 */
public record EntitlementResponse(
        Long id,
        String userId,
        String orderId,
        String status,
        int availableQuantity) {

    public static EntitlementResponse from(Entitlement entitlement) {
        return new EntitlementResponse(
                entitlement.getId(),
                entitlement.getUserId(),
                entitlement.getOrderId(),
                entitlement.getStatus().name(),
                entitlement.getAvailableQuantity());
    }
}
