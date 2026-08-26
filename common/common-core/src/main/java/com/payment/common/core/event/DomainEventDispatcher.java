package com.payment.common.core.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按事件类型名索引本服务的 {@link DomainEventHandler}，把投递进来的事件路由到对应处理器。
 * 消费方（inbox）用它把跨服务 HTTP 投递的事件转交本地处理器。
 */
public class DomainEventDispatcher {

    private final Map<String, DomainEventHandler<?>> handlersByType = new HashMap<>();

    public DomainEventDispatcher(List<DomainEventHandler<?>> handlers) {
        for (DomainEventHandler<?> handler : handlers) {
            handlersByType.put(handler.eventType(), handler);
        }
    }

    /** 是否注册了处理该事件类型的处理器。 */
    public boolean supports(String eventType) {
        return handlersByType.containsKey(eventType);
    }

    /** 把事件投递给对应处理器；无匹配处理器时静默忽略（不破坏投递）。 */
    @SuppressWarnings("unchecked")
    public void dispatch(DomainEvent event) {
        DomainEventHandler<DomainEvent> handler =
                (DomainEventHandler<DomainEvent>) handlersByType.get(event.getEventType());
        if (handler != null) {
            handler.handle(event);
        }
    }
}
