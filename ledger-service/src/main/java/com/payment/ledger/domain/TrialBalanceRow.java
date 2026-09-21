package com.payment.ledger.domain;

/**
 * 期间试算行（spec 031 §10 ②）：按「账户实例 × 币种」聚合的发生额（G1/G2 视图的数据源）。
 */
public record TrialBalanceRow(long accountId, String currency, long debitTotal, long creditTotal) {

    public long diff() {
        return debitTotal - creditTotal;
    }
}
