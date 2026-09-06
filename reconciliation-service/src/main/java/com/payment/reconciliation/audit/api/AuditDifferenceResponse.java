package com.payment.reconciliation.audit.api;

import com.payment.reconciliation.audit.domain.AuditDifference;

/**
 * 审计差异响应（FR-021）。
 */
public record AuditDifferenceResponse(Long id, String kind, String severity, String sourceType, String sourceId,
                                      String reference, Long expectedAmountMinor, Long actualAmountMinor,
                                      String currency, String status, long suspendedAmountMinor,
                                      long adjustedAmountMinor, String detail) {

    public static AuditDifferenceResponse from(AuditDifference d) {
        return new AuditDifferenceResponse(d.getId(), d.getKind().name(), d.getSeverity().name(),
                d.getSourceType(), d.getSourceId(), d.getReference(), d.getExpectedAmountMinor(),
                d.getActualAmountMinor(), d.getCurrency(), d.getStatus().name(),
                d.getSuspendedAmountMinor(), d.getAdjustedAmountMinor(), d.getDetail());
    }
}
