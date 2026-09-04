package com.payment.entitlement.api;

import com.payment.entitlement.domain.Entitlement;

/**
 * 权益查询响应 DTO。
 */
public record EntitlementResponse(
        Long id,
        String userId,
        String orderNo,
        String status,
        int availableQuantity) {

    public static EntitlementResponse from(Entitlement entitlement) {
        return new EntitlementResponse(
                entitlement.getId(),
                entitlement.getUserId(),
                entitlement.getOrderNo(),
                entitlement.getStatus().name(),
                entitlement.getAvailableQuantity());
    }
}
