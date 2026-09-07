package com.payment.common.dto.rpc;

/**
 * 退款结果通知（payment-service → order-service，spec 019 / ADR-0067）。
 *
 * <p>payment 侧退款终态收敛后（同步受理 / 异步渠道回调 / resolve 人工收敛三路统一），
 * 以双号（TXRF + PMRF）通知 order 收口：transaction 层退款单终态 +
 * {@code transactions.refunded_minor} 累加 + order 层状态流转 + 秒杀回补 +
 * 履约终止。幂等：order 按 TXRF 寻址，重复通知可重入吸收。</p>
 */
public record RefundResultNotification(String transactionRefundNo, String paymentRefundNo,
                                       String transactionNo, String orderNo, String paymentNo,
                                       long amountMinor, String currencyCode,
                                       String status, String failureReason) {
}
