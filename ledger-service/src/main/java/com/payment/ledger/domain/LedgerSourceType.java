package com.payment.ledger.domain;

/**
 * 记账来源类型（FR-008）：每条分录冗余来源，支撑按业务事实追溯。
 */
public enum LedgerSourceType {
    PAYMENT,
    REFUND,
    SETTLEMENT
}
