package com.payment.refund.application;

import com.payment.refund.domain.RefundItem;

import java.util.List;

/**
 * 创建退款命令（应用层输入，独立于 API DTO）。
 */
public record CreateRefundCommand(String orderId, Long paymentId, String userId, long amountMinor,
                                  String currencyCode, String reason, String idempotencyKey,
                                  List<RefundItem> items) {
}
