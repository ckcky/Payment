package com.payment.refund.api.dto;

/**
 * 退款事实 DTO（对账）：平台侧已确认的退款事实，供 reconciliation-service 与渠道账单核对。
 *
 * <p>金额为最小货币单位（long），状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。</p>
 */
public record RefundFactResponse(Long refundId, String channelReference, long amountMinor,
                                 String currencyCode, String status) {
}
