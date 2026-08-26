package com.payment.merchant.api.dto;

import com.payment.merchant.domain.Merchant;

/**
 * Outbound representation of a merchant (DTO). Kept separate from the domain entity.
 */
public record MerchantResponse(
        Long id,
        String code,
        String name,
        String status,
        boolean settlementEligible
) {

    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId(),
                merchant.getMerchantCode(),
                merchant.getName(),
                merchant.getStatus().name(),
                merchant.isSettlementEligible()
        );
    }
}
