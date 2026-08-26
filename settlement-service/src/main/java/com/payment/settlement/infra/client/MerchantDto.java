package com.payment.settlement.infra.client;

/**
 * merchant-service 返回的商户 DTO（镜像其 MerchantResponse）。
 */
public record MerchantDto(Long id, String code, String name, String status, boolean settlementEligible) {
}
