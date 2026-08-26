package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 履约失败事实（fulfillment 模块产生），供上层感知与告警；不回写 Payment 成功事实。
 */
public final class FulfillmentFailed extends DomainEvent {

    public static final String EVENT_TYPE = "FulfillmentFailed";

    private final String orderId;
    private final String reason;

    public FulfillmentFailed(String sourceModule, String fulfillmentId, long version, String orderId, String reason) {
        super(sourceModule, fulfillmentId, version);
        this.orderId = orderId;
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }
}
