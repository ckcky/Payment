package com.payment.settlement.application;

/**
 * 结算 → ledger-service 的出站记账端口（ADR-0023）：满足 Constitution §II.3「一切资金变动 MUST 经 ledger」。
 * 生产用 Feign 实现，测试用 fake。
 */
public interface LedgerPostingGateway {

    /** 收敛为 SUCCEEDED 且净额 &gt; 0 时触发；幂等键固定 {@code SETTLEMENT:<idempotencyKey>}。 */
    void postSettlement(String idempotencyKey, Long batchId, long netMinor, String currencyCode);
}
