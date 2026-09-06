package com.payment.common.dto.rpc;

/**
 * 退款命令请求（order-service → payment-service，Feature 016 立项 / spec 019 / ADR-0067 升级）。
 *
 * <p><b>spec 019 两层退款单</b>：order transaction 层生成交易层退款单号
 * {@code transactionRefundNo}（TXRF+雪花）后发起退款命令；payment 据此生成
 * 支付层退款执行单（PMRF+雪花），幂等键 = transactionRefundNo（同 TXRF 重试
 * 返回同一执行单，可重入回放——微信模式语义）。响应 {@code refundNo} 即 PMRF，
 * 由 order 回填 {@code transaction_refunds.payment_refund_no}（双号互记）。</p>
 *
 * <p>金额为本次退款金额（surplus 场景为 surplus 支付单全额）。</p>
 */
public record RefundCommandRequest(String transactionRefundNo, String transactionNo, String paymentNo,
                                   String orderNo, String userId, long amountMinor, String currencyCode) {
}
