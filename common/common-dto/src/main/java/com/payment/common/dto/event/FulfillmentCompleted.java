package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 履约完成事实（fulfillment 模块产生），供 entitlement 消费并授予权益。
 */
public final class FulfillmentCompleted extends DomainEvent {

    public static final String EVENT_TYPE = "FulfillmentCompleted";

    private final String orderId;
    private final String userId;

    public FulfillmentCompleted(String sourceModule, String fulfillmentId, long version,
                                String orderId, String userId) {
        super(sourceModule, fulfillmentId, version);
        this.orderId = orderId;
        this.userId = userId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getFulfillmentId() {
        return getAggregateId();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }
}
