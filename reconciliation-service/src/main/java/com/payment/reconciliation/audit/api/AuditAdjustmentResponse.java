package com.payment.reconciliation.audit.api;

import com.payment.reconciliation.audit.domain.AuditAdjustment;

/**
 * 挂账/调账台账响应（FR-019 / FR-021）。
 */
public record AuditAdjustmentResponse(Long id, String adjustNo, Long differenceId, String kind,
                                      String debitAccountCode, String creditAccountCode,
                                      long amountMinor, String currency, String postingNo,
                                      String status, String operator, String reviewer, String reason) {

    public static AuditAdjustmentResponse from(AuditAdjustment a) {
        return new AuditAdjustmentResponse(a.getId(), a.getAdjustNo(), a.getDifferenceId(), a.getKind().name(),
                a.getDebitAccountCode(), a.getCreditAccountCode(), a.getAmountMinor(), a.getCurrency(),
                a.getPostingNo(), a.getStatus(), a.getOperator(), a.getReviewer(), a.getReason());
    }
}
