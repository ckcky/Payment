package com.payment.common.core.event;

import java.util.List;

/**
 * 事务性 Outbox 存储边界：保存「待投递」的领域事件，直到被继电器标记为已投递。
 *
 * <p>写方（{@link DomainEventPublisher} 实现）在同一本地事务内把发布意图写入 Outbox，
 * 继电器异步投递并保证至少一次；消费方幂等去重保证「最多一次」业务效果。MVP 用内存实现
 * （无真实事务），语义在 {@code OutboxRelayTest} 中验证。</p>
 */
public interface OutboxStore {

    /** 追加一条待投递事件（发布意图）。 */
    void append(DomainEvent event);

    /** 返回所有尚未投递成功的事件。 */
    List<DomainEvent> listUndispatched();

    /** 标记某事件已投递成功（从待投递集合移除）。 */
    void markDispatched(String eventId);
}
