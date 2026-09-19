package com.payment.order.api;

import com.payment.order.domain.OrderEvent;
import com.payment.order.domain.OrderEventLogRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单事件轨迹对外接口（spec 029 / FR-403、T53）。
 *
 * <p><b>只读投影（INV-5）</b>：返回按时间升序的事件列表；数据来自 {@code order_event_log}
 * （trace 消费组落表）。删除轨迹表不影响业务链路（FR-404）。</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderTimelineController {

    private final OrderEventLogRepository eventLogRepository;

    public OrderTimelineController(OrderEventLogRepository eventLogRepository) {
        this.eventLogRepository = eventLogRepository;
    }

    /** 轨迹条目响应（含 traceId，便于跨异步边界排障）。 */
    public record TimelineEvent(String eventType, String topic, String msgId, String traceId,
                                String producer, String payloadJson, Instant occurredAt) {
    }

    public record TimelineResponse(String orderNo, int count, List<TimelineEvent> events) {
    }

    /** 按订单号返回完整事件时序（升序）。 */
    @GetMapping("/{orderNo}/timeline")
    public TimelineResponse timeline(@PathVariable String orderNo) {
        List<TimelineEvent> events = eventLogRepository.findByOrderNo(orderNo).stream()
                .map(OrderTimelineController::toEvent)
                .toList();
        return new TimelineResponse(orderNo, events.size(), events);
    }

    private static TimelineEvent toEvent(OrderEvent e) {
        return new TimelineEvent(e.eventType(), e.topic(), e.msgId(), e.traceId(),
                e.producer(), e.payloadJson(), e.occurredAt());
    }
}
