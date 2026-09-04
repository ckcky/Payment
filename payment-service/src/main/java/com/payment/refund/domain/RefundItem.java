package com.payment.refund.domain;

/**
 * 退款明细：一次退款针对的订单明细及其申请金额（最小货币单位，long）。
 *
 * <p>退款后处理（履约撤销/权益吊销）可据此定位到具体明细，但处理结果由下游领域决定。</p>
 */
public record RefundItem(String orderItemId, long amountMinor) {
}
