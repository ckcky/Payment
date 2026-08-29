package com.payment.settlement.domain;

/**
 * 调整项状态（ADR-0022）：ACTIVE 参与净额计算；REVOKED 不参与（建批后撤销/冲正不在本 Feature）。
 */
public enum AdjustmentStatus {
    ACTIVE,
    REVOKED
}
