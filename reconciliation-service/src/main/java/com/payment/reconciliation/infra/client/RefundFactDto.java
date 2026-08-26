package com.payment.reconciliation.infra.client;

/**
 * 退款事实 RPC 响应 DTO（镜像 refund-service 的退款已确认事实）。
 * 金额为最小货币单位（long）。
 */
public record RefundFactDto(Long refundId, String channelReference, long amountMinor,
                            String currencyCode, String status) {
}
