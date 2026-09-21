package com.payment.reconciliation.api;

import com.payment.reconciliation.domain.ReconciliationRepository;

import java.util.List;

/**
 * 差异分页查询响应（spec §10.1）。
 */
public record DifferencesPageResponse(List<DifferenceLedgerResponse> items, long total,
                                      int page, int size) {

    public static DifferencesPageResponse from(ReconciliationRepository.DifferencePage result, int page, int size) {
        return new DifferencesPageResponse(result.items().stream().map(DifferenceLedgerResponse::from).toList(),
                result.total(), page, size);
    }
}
