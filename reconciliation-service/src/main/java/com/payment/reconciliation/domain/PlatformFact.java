package com.payment.reconciliation.domain;

/**
 * 平台侧已确认业务事实（只读快照，来自 payment-service / refund-service 的 RPC）。
 * 对账永不修改原始 Payment/Refund，只消费这份事实副本。
 *
 * @param reference    渠道引用（如支付/退款的 channelReference）
 * @param type         事实类型："PAYMENT" / "REFUND"
 * @param amountMinor  金额（最小货币单位，long，禁止浮点）
 * @param currencyCode 币种
 * @param status       平台侧状态（枚举名 String）
 */
public record PlatformFact(String reference, String type, long amountMinor, String currencyCode, String status) {
}
