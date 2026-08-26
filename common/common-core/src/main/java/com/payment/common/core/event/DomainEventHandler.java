package com.payment.common.core.event;

/**
 * 领域事件处理器边界：一个服务声明它能消费的某类跨服务事实。
 *
 * <p>实现必须幂等（同一事件重复投递不产生重复业务动作）。{@link #eventType()} 返回处理的
 * 事件类型名（与 {@link DomainEvent#getEventType()} 对应）。</p>
 */
public interface DomainEventHandler<E extends DomainEvent> {

    /** 该处理器消费的事件类型名，如 {@code PaymentSucceeded}。 */
    String eventType();

    /** 处理一条事件（必须幂等）。 */
    void handle(E event);
}
