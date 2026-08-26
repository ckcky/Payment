package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 支付失败事实（payment 模块产生），供 order 标记失败。
 */
public final class PaymentFailed extends DomainEvent {

    public static final String EVENT_TYPE = "PaymentFailed";

    private final String orderId;
    private final String transactionId;
    private final String failureReason;

    public PaymentFailed(String sourceModule, String paymentId, long version,
                         String orderId, String transactionId, String failureReason) {
        super(sourceModule, paymentId, version);
        this.orderId = orderId;
        this.transactionId = transactionId;
        this.failureReason = failureReason;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getPaymentId() {
        return getAggregateId();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
