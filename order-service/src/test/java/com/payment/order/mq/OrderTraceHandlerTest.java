package com.payment.order.mq;

import com.payment.common.mq.EventEnvelope;
import com.payment.order.domain.OrderEvent;
import com.payment.order.domain.OrderEventLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trace 消费组落表单测（spec 029 / T52-T53、FR-402/403）。
 *
 * <p>断言：事件按 orderNo 落入轨迹；同 msgId 重复投递被幂等吸收（INV-2 / SC-6）；
 * 轨迹为只读投影，不产生业务副作用（INV-5）。</p>
 */
class OrderTraceHandlerTest {

    /** 内存轨迹仓储（msgId 去重 + 插入序）。 */
    private static final class InMemoryEventLog implements OrderEventLogRepository {
        final Set<String> msgIds = new LinkedHashSet<>();
        final List<OrderEvent> events = new ArrayList<>();

        @Override
        public boolean append(OrderEvent event) {
            if (!msgIds.add(event.msgId())) {
                return false;
            }
            events.add(event);
            return true;
        }

        @Override
        public List<OrderEvent> findByOrderNo(String orderNo) {
            return events.stream().filter(e -> orderNo.equals(e.orderNo())).toList();
        }
    }

    private static EventEnvelope envelope(String topic, String msgId, String orderNo) {
        return new EventEnvelope(msgId, topic, topic, orderNo, "TR-1", "order",
                Instant.now(), Map.of("orderNo", orderNo, "amountMinor", 100L));
    }

    @Test
    void traceLandsEventForOrder() {
        InMemoryEventLog log = new InMemoryEventLog();
        new OrderTraceHandler(log).onEvent(envelope("order.paid", "m-1", "OR-1"));

        assertThat(log.findByOrderNo("OR-1")).hasSize(1);
        assertThat(log.events.get(0).eventType()).isEqualTo("order.paid");
        assertThat(log.events.get(0).traceId()).isEqualTo("TR-1");
    }

    @Test
    void duplicateMsgIdIsAbsorbed() {
        InMemoryEventLog log = new InMemoryEventLog();
        OrderTraceHandler handler = new OrderTraceHandler(log);

        handler.onEvent(envelope("order.paid", "m-1", "OR-1"));
        handler.onEvent(envelope("order.paid", "m-1", "OR-1"));
        handler.onEvent(envelope("order.paid", "m-2", "OR-1"));

        // SC-6：同 msgId 只落一行；不同 msgId 各自成行
        assertThat(log.events).hasSize(2);
    }

    @Test
    void eventsWithoutOrderNoAreSkipped() {
        InMemoryEventLog log = new InMemoryEventLog();
        EventEnvelope noOrder = new EventEnvelope("m-9", "order.paid", "order.paid", null,
                "TR-1", "order", Instant.now(), Map.of());

        new OrderTraceHandler(log).onEvent(noOrder);

        assertThat(log.events).isEmpty();
    }
}
