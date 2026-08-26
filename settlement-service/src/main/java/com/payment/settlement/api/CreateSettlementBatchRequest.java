package com.payment.settlement.api;

/**
 * 创建结算批次请求。
 */
public record CreateSettlementBatchRequest(String merchantId, String period, String idempotencyKey) {
}
