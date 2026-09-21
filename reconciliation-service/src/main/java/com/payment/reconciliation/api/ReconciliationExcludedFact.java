package com.payment.reconciliation.api;

/**
 * 结算扣减项（032/G4）：一条未收口差异对该事实在结算口径下的净影响。
 * 事实仍出现在 {@code facts} 中（TC-032-11：挂账后放行仍含事实、只扣净影响）。
 *
 * @param reference   渠道引用/业务单号
 * @param type        事实类型 PAYMENT / REFUND（其余类型不进入结算口径）
 * @param amountMinor 净影响扣减额（PLATFORM_ONLY/STATUS_MISMATCH=事实全额；AMOUNT_MISMATCH=|期望−实际|）
 * @param reason      扣减原因（差异类型名，如 PLATFORM_ONLY）
 */
public record ReconciliationExcludedFact(String reference, String type, long amountMinor, String reason) {
}
