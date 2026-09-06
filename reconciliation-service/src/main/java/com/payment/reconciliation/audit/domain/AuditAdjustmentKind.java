package com.payment.reconciliation.audit.domain;

/**
 * 挂账 / 调账动作类型（FR-014 / FR-015）。
 */
public enum AuditAdjustmentKind {
    /** 挂账：差额安置到 SUSPENSE 过渡科目。 */
    SUSPEND,
    /** 补记：确认漏记，补上分录。 */
    SUPPLEMENT,
    /** 红冲：确认多记，原分录反向冲平（append-only，不删原分录）。 */
    REVERSE,
    /** 更正：红蓝字，先反向冲原分录再记正确分录。 */
    CORRECT,
    /** 转出：从 SUSPENSE 转出到目标科目（查清归属）。 */
    TRANSFER,
    /** 核销：MVP 默认不开放（audit.adjust.write-off.enabled=false，plan §11 ⓐ）。 */
    WRITE_OFF
}
