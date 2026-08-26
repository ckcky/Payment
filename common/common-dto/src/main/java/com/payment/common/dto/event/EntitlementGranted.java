package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 权益授予成功事实（entitlement 模块产生），供可观测/审计消费。
 */
public final class EntitlementGranted extends DomainEvent {

    public static final String EVENT_TYPE = "EntitlementGranted";

    private final String orderId;

    public EntitlementGranted(String sourceModule, String entitlementId, long version, String orderId) {
        super(sourceModule, entitlementId, version);
        this.orderId = orderId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getEntitlementId() {
        return getAggregateId();
    }

    public String getOrderId() {
        return orderId;
    }
}
