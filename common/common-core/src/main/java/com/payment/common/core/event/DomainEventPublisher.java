package com.payment.common.core.event;

/**
 * 事件发布边界（plan T011）：模块/服务内的后置副作用通过它发布领域事件。
 *
 * <p>不创建「全局业务事件模块」；事件归属于产生它的模块。微服务形态下，本接口的实现是
 * 事务性 Outbox（无 MQ）：先在同一本地事务内持久化发布意图，再异步投递，保证至少一次。</p>
 */
public interface DomainEventPublisher {

    /** 发布一个领域事件（发布意图需可持久化追踪，见 plan §7/§8）。 */
    void publish(DomainEvent event);
}
