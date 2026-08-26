package com.payment.common.core.event;

/**
 * {@link DomainEventPublisher} 的事务性 Outbox 实现：发布即写入 Outbox（发布意图持久化），
 * 由 {@link OutboxRelay} 异步投递。生产实现会在与领域状态变更相同的本地事务内写入；MVP
 * 内存实现中「发布」与「状态保存」顺序执行，事务语义由后续持久化层补齐。
 */
public class OutboxPublisher implements DomainEventPublisher {

    private final OutboxStore store;

    public OutboxPublisher(OutboxStore store) {
        this.store = store;
    }

    @Override
    public void publish(DomainEvent event) {
        store.append(event);
    }
}
