package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 支付成功事实（payment 模块产生），供 order（标记已支付）、fulfillment（触发履约）、
 * entitlement（触发权益）消费。
 *
 * <p>只携带跨服务需要的原始字段，不暴露 payment 模块内部实体（plan §7 / T019）。</p>
 */
public final class PaymentSucceeded extends DomainEvent {

    public static final String EVENT_TYPE = "PaymentSucceeded";

    private final String orderId;
    private final String transactionId;
    private final String userId;
    private final long amountMinor;
    private final String currencyCode;

    public PaymentSucceeded(String sourceModule, String paymentId, long version,
                            String orderId, String transactionId, String userId,
                            long amountMinor, String currencyCode) {
        super(sourceModule, paymentId, version);
        this.orderId = orderId;
        this.transactionId = transactionId;
        this.userId = userId;
        this.amountMinor = amountMinor;
        this.currencyCode = currencyCode;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    /** 支付聚合身份（== {@link #getAggregateId()}）。 */
    public String getPaymentId() {
        return getAggregateId();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
