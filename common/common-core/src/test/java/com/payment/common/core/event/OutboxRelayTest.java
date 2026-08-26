package com.payment.common.core.event;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务性 Outbox 语义测试：发布写入 Outbox、继电器至少一次投递、成功投递后移除、重复投递无副作用。
 */
class OutboxRelayTest {

    private static final class TestEvent extends DomainEvent {
        TestEvent(String id) {
            super("test", id, 1L);
        }

        @Override
        public String getEventType() {
            return "TestEvent";
        }
    }

    private static final class RecordingHandler implements DomainEventHandler<TestEvent> {
        final List<TestEvent> handled = new ArrayList<>();

        @Override
        public String eventType() {
            return "TestEvent";
        }

        @Override
        public void handle(TestEvent event) {
            handled.add(event);
        }
    }

    private static DomainEventDispatcher dispatcherOf(DomainEventHandler<?>... handlers) {
        List<DomainEventHandler<?>> list = new ArrayList<>();
        for (DomainEventHandler<?> h : handlers) {
            list.add(h);
        }
        return new DomainEventDispatcher(list);
    }

    @Test
    void publisherAppendsToStore() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        DomainEventPublisher publisher = new OutboxPublisher(store);
        publisher.publish(new TestEvent("a"));
        assertThat(store.listUndispatched()).hasSize(1);
    }

    @Test
    void relayDispatchesEachEventExactlyOnce() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        RecordingHandler handler = new RecordingHandler();
        OutboxRelay relay = new OutboxRelay(store, dispatcherOf(handler));

        store.append(new TestEvent("a"));
        store.append(new TestEvent("b"));

        assertThat(relay.dispatchPending()).isEqualTo(2);
        assertThat(handler.handled).hasSize(2);
        assertThat(store.listUndispatched()).isEmpty();

        assertThat(relay.dispatchPending()).isZero();
        assertThat(handler.handled).hasSize(2);
    }

    @Test
    void dispatcherIgnoresUnknownEventType() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxRelay relay = new OutboxRelay(store, dispatcherOf());

        store.append(new TestEvent("a"));
        assertThat(relay.dispatchPending()).isEqualTo(1);
        assertThat(store.listUndispatched()).isEmpty();
    }
}
