package com.payment.settlement.api;

import com.payment.settlement.domain.SettlementBatch;

/**
 * 结算批次响应 DTO。状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 */
public record SettlementBatchResponse(Long id, String batchNo, String merchantId, String period, String currencyCode,
                                      long incomeMinor, long refundMinor, long adjustmentMinor,
                                      long netMinor, String status) {

    public static SettlementBatchResponse from(SettlementBatch batch) {
        return new SettlementBatchResponse(
                batch.getId(),
                batch.getBatchNo(),
                batch.getMerchantId(),
                batch.getPeriod(),
                batch.getCurrencyCode(),
                batch.getIncomeMinor(),
                batch.getRefundMinor(),
                batch.getAdjustmentMinor(),
                batch.getNetMinor(),
                batch.getStatus().name());
    }
}
