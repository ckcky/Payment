package com.payment.settlement.application;

/**
 * 商户视图（本地端口值对象）：仅承载结算所需的商户事实。
 */
public record MerchantView(Long id, String status, boolean settlementEligible) {
}
