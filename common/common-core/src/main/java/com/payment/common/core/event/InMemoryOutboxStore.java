package com.payment.common.core.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存 Outbox 实现：按事件 ID 去重、按追加顺序返回待投递事件。仅用于 MVP 单机演示与测试。
 */
public class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, DomainEvent> pending = new LinkedHashMap<>();

    @Override
    public synchronized void append(DomainEvent event) {
        pending.put(event.getEventId(), event);
    }

    @Override
    public synchronized List<DomainEvent> listUndispatched() {
        return new ArrayList<>(pending.values());
    }

    @Override
    public synchronized void markDispatched(String eventId) {
        pending.remove(eventId);
    }
}
