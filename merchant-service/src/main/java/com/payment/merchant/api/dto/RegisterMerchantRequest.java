package com.payment.merchant.api.dto;

/**
 * Inbound request body for registering a merchant.
 */
public record RegisterMerchantRequest(
        String code,
        String name,
        String settlementAccountRef
) {
}
