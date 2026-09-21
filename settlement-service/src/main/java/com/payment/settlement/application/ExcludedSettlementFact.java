package com.payment.settlement.application;

/**
 * 结算扣减项（032/G4）：对账侧未收口差异对某事实在结算口径下的净影响。
 *
 * @param reference   渠道引用/业务单号
 * @param type        扣减落点 PAYMENT / REFUND（扣 income 或扣 refund）
 * @param amountMinor 净影响扣减额（正数）
 * @param reason      扣减原因（对账差异类型名，如 PLATFORM_ONLY）
 */
public record ExcludedSettlementFact(String reference, String type, long amountMinor, String reason) {
}
