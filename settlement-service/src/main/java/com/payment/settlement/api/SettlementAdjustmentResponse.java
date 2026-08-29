package com.payment.settlement.api;

import com.payment.settlement.domain.SettlementAdjustment;

/**
 * 结算调整项响应 DTO。
 */
public record SettlementAdjustmentResponse(Long id, String idempotencyKey, String merchantId, String period,
                                            long amountMinor, String direction, String currencyCode,
                                            String reason, String operator, String status, String createdAt) {

    public static SettlementAdjustmentResponse from(SettlementAdjustment a) {
        return new SettlementAdjustmentResponse(a.getId(), a.getIdempotencyKey(), a.getMerchantId(),
                a.getPeriod(), a.getAmountMinor(), a.getDirection().name(), a.getCurrencyCode(),
                a.getReason(), a.getOperator(), a.getStatus().name(), a.getCreatedAt().toString());
    }
}
