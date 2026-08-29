package com.payment.settlement.domain;

/**
 * 调整项方向（ADR-0022）：用正金额 + 方向枚举表达方向，禁止用负数金额表达方向
 * （负数会与「金额非负」不变量互相打假）。CREDIT 增加净额，DEBIT 减少净额。
 */
public enum AdjustmentDirection {
    CREDIT,
    DEBIT
}
