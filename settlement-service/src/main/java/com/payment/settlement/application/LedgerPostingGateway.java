package com.payment.settlement.application;

/**
 * 结算 → ledger-service 的出站记账端口（spec 031 §9 / ADR-0077，替换 ADR-0023 的直传分录契约）：
 * 满足 Constitution §II.3「一切资金变动 MUST 经 ledger」。生产用 Feign 实现，测试用 fake。
 */
public interface LedgerPostingGateway {

    /** 批次收敛为 SUCCEEDED 且净额非 0 时触发（H3：负净额也记账，反向分录由账本展开）。 */
    void postMerchantSettlement(MerchantSettlementFacts facts);

    /**
     * 已确认结算事实（Financial Fact）。
     *
     * @param batchNo      批次业务单号（账本 sourceId，M1 收编——弃数值 batchId，ADR-0063）
     * @param merchantId   商户号（MERCHANT_SETTLEMENT 必填，商户实例解析用）
     * @param netMinor     批次净额，**带符号**（负值走 §7.5 反向分录，0 不发事件）
     * @param currencyCode 币种
     */
    record MerchantSettlementFacts(String batchNo, String merchantId, long netMinor, String currencyCode) {
    }
}
