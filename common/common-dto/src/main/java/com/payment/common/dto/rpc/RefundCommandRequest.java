package com.payment.common.dto.rpc;

/**
 * 自动退款命令请求（order-service → payment-service，Feature 016 / ADR-0054）。
 *
 * <p>order transaction 层判定「重复 / 超额（surplus）」后，以 {@code transactionNo + paymentNo}
 * 向 payment 发起自动退款命令；payment 仅作执行方（能力提供方），按退款编排三步链执行
 * （生成 refundNo → 落退款渠道尝试记录 → 调外部渠道，FR-017）。金额为 surplus 支付单全额。</p>
 */
public record RefundCommandRequest(String transactionNo, String paymentNo, String orderNo,
                                   String userId, long amountMinor, String currencyCode) {
}
