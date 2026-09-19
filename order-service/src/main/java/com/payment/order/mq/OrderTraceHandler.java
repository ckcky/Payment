package com.payment.order.mq;

import com.payment.common.mq.EventEnvelope;
import com.payment.order.domain.OrderEvent;
import com.payment.order.domain.OrderEventLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * trace 消费组路由（spec 029 / 批次 E / T52、FR-402）。
 *
 * <p>订阅**全部** topic，把每条事件落到 {@code order_event_log}（只读投影，INV-5）。
 * 消费失败进 DLQ 不影响业务组（trace 组独立位点、独立组名 {@code trace}）。</p>
 *
 * <p>去重：同 msgId 重复投递撞唯一键被仓储吸收（INV-2）。</p>
 */
@Component
public class OrderTraceHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderTraceHandler.class);

    private final OrderEventLogRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrderTraceHandler(OrderEventLogRepository repository) {
        this.repository = repository;
    }

    /** 任意 topic 的事件 → 轨迹落表（幂等：msgId）。 */
    void onEvent(EventEnvelope envelope) {
        String orderNo = resolveOrderNo(envelope);
        if (orderNo == null) {
            // 非订单维度事件（极少数）忽略：轨迹表以 order_no 为检索维度
            log.debug("trace 事件无 orderNo，跳过 msgId={} topic={}", envelope.msgId(), envelope.topic());
            return;
        }
        boolean inserted = repository.append(new OrderEvent(
                orderNo,
                envelope.topic(),
                envelope.topic(),
                envelope.msgId(),
                envelope.traceId(),
                envelope.producer(),
                toJson(envelope),
                envelope.occurredAt()));
        log.debug("trace 落表 orderNo={} topic={} msgId={} inserted={}",
                orderNo, envelope.topic(), envelope.msgId(), inserted);
    }

    /** 从负载取订单号（INV-6：一律业务单号，绝不用数值 id）。 */
    private static String resolveOrderNo(EventEnvelope envelope) {
        String orderNo = envelope.str("orderNo");
        if (orderNo != null) {
            return orderNo;
        }
        // refund.result / refund.succeeded 等以 refundNo 为 bizNo，但负载带 orderNo；兜底用 bizNo
        String bizNo = envelope.bizNo();
        return bizNo != null && bizNo.startsWith("OR") ? bizNo : null;
    }

    private String toJson(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope.payload());
        } catch (JsonProcessingException ex) {
            log.warn("轨迹负载序列化失败 msgId={}", envelope.msgId(), ex);
            return "{}";
        }
    }
}
