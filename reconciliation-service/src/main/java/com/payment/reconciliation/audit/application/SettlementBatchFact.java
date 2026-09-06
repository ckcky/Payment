package com.payment.reconciliation.audit.application;

/**
 * 结算批次审计事实（settlement /internal/settlements/audit-facts）。
 *
 * <p>注意：ledger 侧 SETTLEMENT posting 的 sourceId 是批次 {@code id}（见
 * settlement FeignLedgerPostingGateway），跨账核对以此为键。</p>
 */
public record SettlementBatchFact(Long id, String batchNo, String status,
                                  long netMinor, String currency) {

    public boolean confirmed() {
        return "SUCCEEDED".equals(status);
    }
}
