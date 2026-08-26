package com.payment.reconciliation.domain;

/**
 * 渠道账单条目（本地 Mock/预置 fixture，非真实渠道）。
 *
 * @param reference    渠道引用
 * @param amountMinor  金额（最小货币单位，long，禁止浮点）
 * @param currencyCode 币种
 * @param status       渠道侧状态
 */
public record ChannelStatement(String reference, long amountMinor, String currencyCode, String status) {
}
