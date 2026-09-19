package com.payment.fulfillment.mq;

import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.TransactionalProducer;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * fulfillment 侧事件发布门面（spec 029 / 批次 D / T44、T46、FR-206、FR-207）。
 *
 * <ul>
 *   <li>{@code fulfillment.completed}（点对点 → entitlement）：替代
 *       {@code EntitlementGateway.notifyFulfillmentCompleted}（FR-206）。</li>
 *   <li>{@code fulfillment.revoked}（点对点 → entitlement）：替代
 *       {@code EntitlementGateway.revokeOnRefund}（FR-207）。</li>
 * </ul>
 *
 * <p><b>traceId 继承（FR-604）</b>：消费端再生产新事件时，
 * {@code producer.envelope()} 从当前 {@code TraceContext} 取 traceId——
 * {@link StreamConsumer} 已在消费前把信封 traceId 放入 MDC/TraceContext，
 * 故此处自然继承同一链路 traceId。</p>
 *
 * <p>{@code payment.mq.enabled=false} 时本 Bean 不注册，调用方回落同步 Feign（FR-306）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FulfillmentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentEventPublisher.class);

    private final TransactionalProducer producer;

    public FulfillmentEventPublisher(TransactionalProducer producer) {
        this.producer = producer;
    }

    /** 发布 {@code fulfillment.completed}（点对点 → entitlement）：授予权益。 */
    public void publishFulfillmentCompleted(FulfillmentCompletedRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fulfillmentId", request.fulfillmentId());
        payload.put("orderNo", request.orderNo());
        payload.put("userId", request.userId());
        publish(MqTopics.FULFILLMENT_COMPLETED, String.valueOf(request.fulfillmentId()), payload);
        log.info("MQ 发布 fulfillment.completed [fulfillmentId={}, orderNo={}]",
                request.fulfillmentId(), request.orderNo());
    }

    /** 发布 {@code fulfillment.revoked}（点对点 → entitlement）：回收权益。 */
    public void publishFulfillmentRevoked(RefundPostProcessRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundNo", request.refundNo());
        payload.put("paymentNo", request.paymentNo());
        payload.put("orderNo", request.orderNo());
        payload.put("userId", request.userId());
        payload.put("reason", request.reason());
        publish(MqTopics.FULFILLMENT_REVOKED, request.refundNo(), payload);
        log.info("MQ 发布 fulfillment.revoked [refundNo={}, orderNo={}]",
                request.refundNo(), request.orderNo());
    }

    private void publish(String topic, String bizNo, Map<String, Object> payload) {
        EventEnvelope envelope = producer.envelope(topic, bizNo, payload);
        producer.prepare(envelope);
        producer.commit(envelope);
    }
}
