package com.payment.common.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
/**
 * 信封 ↔ Redis Stream field-value 的编解码（FR-101）。
 *
 * <p>落 Stream 时把 {@link EventEnvelope} 拆成多个 field（每个字段一个），而非压成单个 JSON 串——
 * 这样 redis-cli {@code XRANGE mq:stream:order.paid - +} 能直接读出 bizNo / traceId，
 * 排障不必先反序列化。</p>
 */
public final class EnvelopeCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Instant 序列化为 ISO-8601 字符串，便于人工阅读
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EnvelopeCodec() {
    }

    /** 信封 → field-value 数组（XADD 用）。 */
    public static String[] toFields(EventEnvelope e) {
        try {
            return new String[] {
                    "msgId", nz(e.msgId()),
                    "topic", nz(e.topic()),
                    "eventType", nz(e.eventType()),
                    "bizNo", nz(e.bizNo()),
                    "traceId", nz(e.traceId()),
                    "producer", nz(e.producer()),
                    "occurredAt", e.occurredAt() == null ? "" : e.occurredAt().toString(),
                    "payload", MAPPER.writeValueAsString(e.payload() == null ? java.util.Map.of() : e.payload())
            };
        } catch (JsonProcessingException ex) {
            throw new MqException("信封序列化失败 [topic=" + e.topic() + ", msgId=" + e.msgId() + "]", ex);
        }
    }

    /** field-value Map → 信封（XREADGROUP / 半消息 Hash 读回用）。 */
    public static EventEnvelope fromFields(java.util.Map<String, String> f) {
        try {
            String occurredAt = f.get("occurredAt");
            String payloadJson = f.get("payload");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> payload = payloadJson == null || payloadJson.isBlank()
                    ? java.util.Map.of()
                    : MAPPER.readValue(payloadJson, java.util.Map.class);
            return new EventEnvelope(
                    f.get("msgId"),
                    f.get("topic"),
                    f.get("eventType"),
                    f.get("bizNo"),
                    f.get("traceId"),
                    f.get("producer"),
                    occurredAt == null || occurredAt.isBlank() ? null : java.time.Instant.parse(occurredAt),
                    payload);
        } catch (JsonProcessingException ex) {
            throw new MqException("信封反序列化失败 [msgId=" + f.get("msgId") + "]", ex);
        }
    }

    public static String header(String traceId, String bizNo, String eventType) {
        return "trace=" + nz(traceId) + " biz=" + nz(bizNo) + " type=" + nz(eventType);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
