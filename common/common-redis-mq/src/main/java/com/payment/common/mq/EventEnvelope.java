package com.payment.common.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * 事件信封（spec 029 / FR-101 / ADR-0074 D5）。
 *
 * <p>通道上承载的唯一载体形态；所有 topic 共用此结构，差异只在 {@code topic} 与 {@code payload}。
 * 落 Redis Stream 时序列化为 field-value 对（每个字段一个 field），便于 redis-cli 直接排障。</p>
 *
 * <ul>
 *   <li>{@code msgId}：消息唯一标识（幂等与半消息索引键，由生产端生成 UUID）</li>
 *   <li>{@code topic}：逻辑通道名（如 {@code order.paid}），对应 Stream 键 {@code mq:stream:{topic}}</li>
 *   <li>{@code eventType}：业务事件类型（与 topic 同名，便于消费端按类型分派）</li>
 *   <li>{@code bizNo}：业务单号（orderNo / paymentNo / refundNo，INV-6 禁用数值 ID），
 *       同时作为 MDC {@code bizNo} 维度（FR-605）的来源</li>
 *   <li>{@code traceId}：跨异步边界的链路关联键（INV-9 / FR-601），消费端据此恢复 MDC</li>
 *   <li>{@code producer}：生产者服务名，便于排障归属</li>
 *   <li>{@code occurredAt}：事件发生时刻</li>
 *   <li>{@code payload}：业务负载（由生产方从自己的库富化，INV-4）</li>
 * </ul>
 *
 * @param msgId      消息唯一标识
 * @param topic      逻辑通道名
 * @param eventType  业务事件类型
 * @param bizNo      业务单号
 * @param traceId    链路关联 ID
 * @param producer   生产者服务名
 * @param occurredAt 事件发生时刻
 * @param payload    业务负载（不可变 Map，序列化为 JSON）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String msgId,
        String topic,
        String eventType,
        String bizNo,
        String traceId,
        String producer,
        Instant occurredAt,
        Map<String, Object> payload) {

    /** payload 取值，缺失返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return payload == null ? null : (T) payload.get(key);
    }

    /** payload 字符串取值。 */
    public String str(String key) {
        Object v = payload == null ? null : payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** payload 长整型取值（容错 Number / 数字字符串）。 */
    public Long num(String key) {
        Object v = payload == null ? null : payload.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 返回复制后的信封（替换 traceId），供回查补投沿用原始 traceId 时构造（FR-603）。 */
    public EventEnvelope withTraceId(String newTraceId) {
        return new EventEnvelope(msgId, topic, eventType, bizNo, newTraceId, producer, occurredAt, payload);
    }
}
