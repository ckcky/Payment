package com.payment.reconciliation.domain;

/**
 * 对账差异处置状态（reconciliation_differences.status，spec 032 §8.3 差异层台账）：
 * 与 audit 侧 5 态处置生命周期语义对齐（spec §7.2），但基础对账侧只保留 4 值：
 * PENDING（待处置）/ SUSPENDED（挂账，资金已入 SUSPENSE）/ ADJUST_FAILED（处置失败，可见不计未收口口径外
 * ——仍属未收口，关批门禁会拦）/ RESOLVED（人工确认收口）。
 */
public enum DifferenceStatus {
    PENDING,
    SUSPENDED,
    ADJUST_FAILED,
    RESOLVED
}
