package com.payment.reconciliation.audit.application;

/**
 * 账证核对业务事实（spec 017 / FR-001）：已确认资金事实，sourceId 一律业务单号（ADR-0063）。
 *
 * @param sourceType  PAYMENT / REFUND / SETTLEMENT
 * @param sourceId    业务单号（paymentNo / refundNo / 结算批次 id）
 * @param reference   渠道引用（可为 null）
 * @param amountMinor 金额（分）
 * @param currency    币种
 * @param status      业务状态（仅 SUCCEEDED 进入比对，FR-012 时点一致性）
 */
public record CertificateFact(String sourceType, String sourceId, String reference,
                              long amountMinor, String currency, String status) {

    public boolean confirmed() {
        return "SUCCEEDED".equals(status);
    }
}
