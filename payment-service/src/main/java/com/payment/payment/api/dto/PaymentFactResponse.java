package com.payment.payment.api.dto;

/**
 * 支付事实 DTO（对账）：平台侧已确认的支付事实，供 reconciliation-service 与渠道账单核对。
 *
 * <p>金额为最小货币单位（long），状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 * 跨系统标识一律业务单号（ADR-0063）：{@code paymentNo}（PM+雪花），禁止数值 paymentId。</p>
 */
public record PaymentFactResponse(String paymentNo, String channelReference, long amountMinor,
                                  String currencyCode, String status) {
}
