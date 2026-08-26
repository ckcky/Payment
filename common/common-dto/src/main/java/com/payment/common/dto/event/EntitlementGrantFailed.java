package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 权益授予失败事实（entitlement 模块产生），供告警与重试。
 */
public final class EntitlementGrantFailed extends DomainEvent {

    public static final String EVENT_TYPE = "EntitlementGrantFailed";

    private final String orderId;
    private final String reason;

    public EntitlementGrantFailed(String sourceModule, String entitlementId, long version, String orderId, String reason) {
        super(sourceModule, entitlementId, version);
        this.orderId = orderId;
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }
}
