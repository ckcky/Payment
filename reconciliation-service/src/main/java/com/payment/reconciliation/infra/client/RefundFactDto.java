package com.payment.reconciliation.infra.client;

/**
 * 退款事实 RPC 响应 DTO（镜像 payment-service（refund 包）的退款已确认事实）。
 * 金额为最小货币单位（long）；跨系统标识一律业务单号（ADR-0063）。
 *
 * @param merchantId 商户号（032/G2：经 payment 单反查补齐）
 */
public record RefundFactDto(String refundNo, String channelReference, long amountMinor,
                            String currencyCode, String status, String merchantId) {

    /** 兼容构造（032 前的 5 字段形态）：merchantId 缺省为 null。 */
    public RefundFactDto(String refundNo, String channelReference, long amountMinor,
                         String currencyCode, String status) {
        this(refundNo, channelReference, amountMinor, currencyCode, status, null);
    }
}
