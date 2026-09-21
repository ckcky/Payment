package com.payment.reconciliation.audit.application;

/**
 * 结算批次审计事实（settlement /internal/settlements/audit-facts）。
 *
 * <p>注意：ledger 侧 SETTLEMENT posting 的 sourceId 是批次 {@code batchNo}（031/M1 收编，
 * 见 settlement FeignLedgerPostingGateway），跨账核对以此为键。</p>
 */
public record SettlementBatchFact(Long id, String batchNo, String status,
                                  long netMinor, String currency, String merchantId) {

    /** 兼容构造（032 前的 5 字段形态）：merchantId 缺省为 null。 */
    public SettlementBatchFact(Long id, String batchNo, String status, long netMinor, String currency) {
        this(id, batchNo, status, netMinor, currency, null);
    }

    public boolean confirmed() {
        return "SUCCEEDED".equals(status);
    }
}
