package com.payment.common.dto.rpc;

/**
 * 记账来源域（spec 031 §5.3 / §6.1）：事件的**业务归属域**（非分录来源冗余）。
 *
 * <p>原 {@code LedgerSourceType.ADJUSTMENT} 收敛为 {@code event_type=ADJUSTMENT +
 * source_type=RECONCILIATION}；历史行不迁移（ledger 侧枚举保留 ADJUSTMENT 只读承接）。</p>
 */
public enum AccountingSourceType {
    PAYMENT,
    REFUND,
    SETTLEMENT,
    RECONCILIATION
}
