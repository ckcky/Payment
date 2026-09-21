package com.payment.refund.api.dto;

/**
 * 退款事实 DTO（对账）：平台侧已确认的退款事实，供 reconciliation-service 与渠道账单核对。
 *
 * <p>金额为最小货币单位（long），状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 * 跨系统标识一律业务单号（ADR-0063）：{@code refundNo}（RF+雪花），禁止数值 refundId。</p>
 *
 * <p>spec 032 / H-032-1：补 {@code merchantId}（经 payment 反查，匹配键 (merchantId, reference)
 * 的事实侧锚点）；历史数据反查不到时为 null，由对账侧按 UNKNOWN_MAPPING 表达。</p>
 */
public record RefundFactResponse(String refundNo, String channelReference, long amountMinor,
                                 String currencyCode, String status, String merchantId) {
}
