package com.payment.refund.application;

import com.payment.refund.domain.RefundItem;

import java.util.List;

/**
 * 创建退款命令（应用层输入，独立于 API DTO）。
 */
public record CreateRefundCommand(String orderNo, String paymentNo, String userId, long amountMinor,
                                  String currencyCode, String reason, String idempotencyKey,
                                  List<RefundItem> items, String transactionNo) {

    /** 兼容构造（无交易上下文）：transactionNo 为 null（手工退款路径）。 */
    public CreateRefundCommand(String orderNo, String paymentNo, String userId, long amountMinor,
                               String currencyCode, String reason, String idempotencyKey,
                               List<RefundItem> items) {
        this(orderNo, paymentNo, userId, amountMinor, currencyCode, reason, idempotencyKey, items, null);
    }
}
