package com.payment.reconciliation.domain;

/**
 * 对账差异类型：金额差异、状态差异、平台独有、渠道独有。
 */
public enum DifferenceType {
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    PLATFORM_ONLY,
    CHANNEL_ONLY
}
