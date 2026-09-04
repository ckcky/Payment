package com.payment.reconciliation.infra.client;

/**
 * 退款事实 RPC 响应 DTO（镜像 payment-service（refund 包）的退款已确认事实）。
 * 金额为最小货币单位（long）；跨系统标识一律业务单号（ADR-0063）。
 */
public record RefundFactDto(String refundNo, String channelReference, long amountMinor,
                            String currencyCode, String status) {
}
