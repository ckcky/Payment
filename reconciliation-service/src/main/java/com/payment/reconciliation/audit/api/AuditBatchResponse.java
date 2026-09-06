package com.payment.reconciliation.audit.api;

import com.payment.reconciliation.audit.domain.AuditBatch;

import java.util.List;

/**
 * 审计批次响应（FR-021）。
 */
public record AuditBatchResponse(String batchNo, String period, String scope, String status,
                                 int checkedCount, int differenceCount,
                                 long suspendedAmountMinor, long adjustedAmountMinor,
                                 String triggeredBy, String startedAt, String finishedAt,
                                 List<AuditDifferenceResponse> differences) {

    public static AuditBatchResponse from(AuditBatch batch, List<AuditDifferenceResponse> differences) {
        return new AuditBatchResponse(batch.getBatchNo(), batch.getPeriod(), batch.getScope().name(),
                batch.getStatus().name(), batch.getCheckedCount(), batch.getDifferences().size(),
                batch.getSuspendedAmountMinor(), batch.getAdjustedAmountMinor(), batch.getTriggeredBy(),
                batch.getStartedAt(), batch.getFinishedAt(), differences);
    }
}
