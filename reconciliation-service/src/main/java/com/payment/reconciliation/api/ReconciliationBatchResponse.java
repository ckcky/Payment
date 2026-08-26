package com.payment.reconciliation.api;

import com.payment.reconciliation.domain.ReconciliationBatch;

/**
 * 对账批次响应 DTO。状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 */
public record ReconciliationBatchResponse(Long id, String period, String source, String status,
                                          int matchCount, int differenceCount) {

    public static ReconciliationBatchResponse from(ReconciliationBatch batch) {
        return new ReconciliationBatchResponse(
                batch.getId(),
                batch.getPeriod(),
                batch.getSource(),
                batch.getStatus().name(),
                batch.getMatches().size(),
                batch.getDifferences().size());
    }
}
