package com.payment.reconciliation.api;

import com.payment.reconciliation.domain.ReconciliationDifference;

/**
 * 对账差异台账响应（spec §10.1 GET differences：拆表后可分页查询）。
 */
public record DifferenceLedgerResponse(String diffNo, Long batchId, Long importId, String period,
                                       String merchantId, String channelCode, String kind, String severity,
                                       String referenceType, String reference, Long expectedAmountMinor,
                                       Long actualAmountMinor, Long feeAmountMinor, String currency,
                                       String status, String dispositionRef, String resolutionNote,
                                       String resolvedBy, String resolvedAt) {

    public static DifferenceLedgerResponse from(ReconciliationDifference d) {
        return new DifferenceLedgerResponse(d.getDiffNo(), d.getBatchId(), d.getImportId(), d.getPeriod(),
                d.getMerchantId(), d.getChannelCode(), d.getKind().name(), d.getKind().severity(),
                d.getReferenceType(), d.getReference(), d.getExpectedAmountMinor(), d.getActualAmountMinor(),
                d.getFeeAmountMinor(), d.getCurrency(), d.getStatus().name(), d.getDispositionRef(),
                d.getResolutionNote(), d.getResolvedBy(), d.getResolvedAt());
    }
}
