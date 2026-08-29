package com.payment.settlement.api;

import com.payment.settlement.domain.AdjustmentDirection;

/**
 * 登记结算调整项请求（ADR-0022）：先于批次登记，独立幂等键与撤销状态。
 */
public record RegisterAdjustmentRequest(String merchantId, String period, String idempotencyKey,
                                        long amountMinor, AdjustmentDirection direction,
                                        String currencyCode, String reason, String operator) {
}
