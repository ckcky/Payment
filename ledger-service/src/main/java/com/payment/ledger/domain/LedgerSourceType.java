package com.payment.ledger.domain;

/**
 * 记账来源域（spec 031 §5.3 / §6.1）：事件的**业务归属域**。
 *
 * <p>031 收敛：原 {@code ADJUSTMENT} 迁为 {@code event_type=ADJUSTMENT + source_type=RECONCILIATION}；
 * {@code ADJUSTMENT} 常量保留仅用于**读取历史行**（历史不迁移），新写入禁止使用。</p>
 */
public enum LedgerSourceType {
    PAYMENT,
    REFUND,
    SETTLEMENT,
    /** 对账/审计域（017 挂账调账在 031 后的归属域）。 */
    RECONCILIATION,
    /** @deprecated 历史行只读承接（spec 031 §5.3）；新事件 MUST 用 RECONCILIATION。 */
    @Deprecated
    ADJUSTMENT
}
