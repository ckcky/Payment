package com.payment.reconciliation.audit.domain;

/**
 * 审计核对范围（spec 017 / FR-010）：账证 / 账账 / 账实 / 账表四维度，ALL 一次跑全部。
 */
public enum AuditScope {
    /** 账证核对：业务事实 ↔ 账本分录（双向覆盖率）。 */
    CERTIFICATE,
    /** 账账核对：借贷平衡 + 科目勾稽 + 跨账（ledger ↔ settlement）。 */
    LEDGER,
    /** 账实核对：账本资金科目发生额 ↔ 渠道账单。 */
    REAL,
    /** 账表核对：对外报表口径 ↔ 业务/账本回算。 */
    REPORT,
    /** 全部四核对一次执行。 */
    ALL
}
