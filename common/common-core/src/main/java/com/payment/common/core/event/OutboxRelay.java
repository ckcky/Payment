package com.payment.common.core.event;

/**
 * Outbox 继电器：把待投递事件按类型路由给本地 {@link DomainEventHandler}，成功后标记已投递。
 *
 * <p>至少一次投递：投递失败的事件保留在 Outbox，供下次重试；消费方幂等保证业务动作最多一次。
 * 生产环境由调度器周期调用 {@link #dispatchPending()}；MVP 由测试/演示显式调用以保证确定性。</p>
 */
public class OutboxRelay {

    private final OutboxStore store;
    private final DomainEventDispatcher dispatcher;

    public OutboxRelay(OutboxStore store, DomainEventDispatcher dispatcher) {
        this.store = store;
        this.dispatcher = dispatcher;
    }

    /**
     * 投递当前所有待投递事件，返回成功投递条数。单条事件投递异常会中断本轮，
     * 未投递成功的事件保留待重试（由调用方决定重试策略）。
     */
    public int dispatchPending() {
        int dispatched = 0;
        for (DomainEvent event : store.listUndispatched()) {
            dispatcher.dispatch(event);
            store.markDispatched(event.getEventId());
            dispatched++;
        }
        return dispatched;
    }
}
