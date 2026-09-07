package com.payment.refund.application;

import com.payment.refund.domain.RefundItem;

import java.util.List;

/**
 * 创建退款命令（应用层输入，独立于 API DTO）。
 *
 * <p><b>spec 019 / ADR-0067</b>：{@code transactionRefundNo}（TXRF）为 order 侧退款命令的
 * 必带上下文，幂等键 = transaction_refund_no（同 TXRF 重试回放同一执行单，可重入）。
 * 存量手工退款路径（无交易上下文）允许为 {@code null}（该入口已随 D6 下线，仅保留兼容）。</p>
 */
public record CreateRefundCommand(String orderNo, String paymentNo, String userId, long amountMinor,
                                  String currencyCode, String reason, String idempotencyKey,
                                  List<RefundItem> items, String transactionNo, String transactionRefundNo) {

    /** 兼容构造（无交易上下文）：transactionNo / transactionRefundNo 为 null。 */
    public CreateRefundCommand(String orderNo, String paymentNo, String userId, long amountMinor,
                               String currencyCode, String reason, String idempotencyKey,
                               List<RefundItem> items) {
        this(orderNo, paymentNo, userId, amountMinor, currencyCode, reason, idempotencyKey, items, null, null);
    }

    /** 兼容构造（有交易上下文、无上层退款单）：spec 019 之前的自动退款形态。 */
    public CreateRefundCommand(String orderNo, String paymentNo, String userId, long amountMinor,
                               String currencyCode, String reason, String idempotencyKey,
                               List<RefundItem> items, String transactionNo) {
        this(orderNo, paymentNo, userId, amountMinor, currencyCode, reason, idempotencyKey, items,
                transactionNo, null);
    }
}
