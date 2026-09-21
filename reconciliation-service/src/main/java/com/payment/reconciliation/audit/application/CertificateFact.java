package com.payment.reconciliation.audit.application;

/**
 * 账证核对业务事实（spec 017 / FR-001）：已确认资金事实，sourceId 一律业务单号（ADR-0063）。
 *
 * @param sourceType  PAYMENT / REFUND / SETTLEMENT
 * @param sourceId    业务单号（paymentNo / refundNo / 结算批次 batchNo，031/M1）
 * @param reference   渠道引用（可为 null）
 * @param amountMinor 金额（分）
 * @param currency    币种
 * @param status      业务状态（仅 SUCCEEDED 进入比对，FR-012 时点一致性）
 * @param merchantId  商户号（032/G2：全链不丢商户维度，可为 null = 兼容窗口）
 */
public record CertificateFact(String sourceType, String sourceId, String reference,
                              long amountMinor, String currency, String status, String merchantId) {

    /** 兼容构造（032 前的 6 字段形态）：merchantId 缺省为 null。 */
    public CertificateFact(String sourceType, String sourceId, String reference,
                           long amountMinor, String currency, String status) {
        this(sourceType, sourceId, reference, amountMinor, currency, status, null);
    }

    public boolean confirmed() {
        return "SUCCEEDED".equals(status);
    }
}
