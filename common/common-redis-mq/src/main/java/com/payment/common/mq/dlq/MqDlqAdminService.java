package com.payment.common.mq.dlq;

import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.mq.EnvelopeCodec;
import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqKeys;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DLQ 管理服务（spec 034 §10 / T20）：死信的读取、回放与清空。
 *
 * <p><b>回放语义（红线）</b>：读出 DLQ 条目的 fields，经 {@link EnvelopeCodec#fromFields}
 * 校验（坏条目拒绝回放、原样留在 DLQ 等人工处置），剥掉 {@code dlqReason} 元数据后
 * <b>原 fields XADD</b> 回 {@code mq:stream:{topic}}，再 XDEL DLQ 条目。重放的就是原始消息
 * 本身——<b>MUST NOT</b> 引入任何「重放标记」绕过消费端幂等；双处理风险由消费端
 * 既有 {@code msgId} + 业务键幂等吸收（TT-10 钉死）。</p>
 *
 * <p><b>故障顺序</b>：先 XADD 主 stream 后 XDEL DLQ——若 XADD 失败则条目仍在 DLQ 可重试；
 * 若 XADD 成功而 XDEL 失败，最坏情况是重放动作重复执行一次，同样被消费端幂等吸收。</p>
 *
 * <p><b>MUST NOT</b> 改 {@code MqKeys} 前缀或 envelope 形状（029 协议稳定，spec §10 红线）。</p>
 */
public class MqDlqAdminService {

    private final StringRedisTemplate redis;
    /** 可空：无审计装配时（如纯测试）不落审计，不影响功能。 */
    private final StructuredAuditLogger audit;

    public MqDlqAdminService(StringRedisTemplate redis, StructuredAuditLogger audit) {
        this.redis = redis;
        this.audit = audit;
    }

    /** DLQ 条目视图（运维只读）：入死信原因 {@code dlqReason} 与原始信封字段并列展示。 */
    public record DlqEntry(String entryId, String msgId, String topic, String eventType, String bizNo,
                           String traceId, String producer, String occurredAt, String dlqReason,
                           String payload) {
    }

    /** XLEN：死信条数（键不存在即 0）。 */
    public long size(String topic) {
        requireTopic(topic);
        Long size = redis.opsForStream().size(MqKeys.dlq(topic));
        return size == null ? 0L : size;
    }

    /** XRANGE 读取（按流内顺序，limit 截断），用于运维视图。 */
    public List<DlqEntry> list(String topic, int limit) {
        requireTopic(topic);
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(MqKeys.dlq(topic), Range.unbounded(),
                        org.springframework.data.redis.connection.Limit.limit()
                                .count(Math.max(1, limit)));
        if (records == null) {
            return List.of();
        }
        return records.stream().map(r -> toEntry(r.getId().getValue(), toStringMap(r.getValue()))).toList();
    }

    /**
     * 回放单条死信：校验 → XADD 主 stream（原 fields）→ XDEL。
     *
     * @return true = 已回放；false = 条目不存在（可能已被处理/清空）
     * @throws com.payment.common.mq.MqException 条目损坏（payload 不可反序列化），拒绝回放、原样保留
     */
    public boolean replay(String topic, String entryId) {
        requireTopic(topic);
        List<MapRecord<String, Object, Object>> found =
                redis.opsForStream().range(MqKeys.dlq(topic), Range.closed(entryId, entryId));
        if (found == null || found.isEmpty()) {
            return false;
        }
        MapRecord<String, Object, Object> record = found.get(0);
        String exactId = record.getId().getValue();
        Map<String, String> fields = toStringMap(record.getValue());
        // 先校验：坏条目在此抛出，DLQ 原样保留（不 XADD、不 XDEL），等人工排障
        EventEnvelope envelope = EnvelopeCodec.fromFields(fields);
        // 剥 DLQ 元数据：重放的是原始消息，dlqReason 只属于死信视图，不回流主 stream
        fields.remove("dlqReason");
        redis.opsForStream().add(MapRecord.create(MqKeys.stream(topic), fields));
        redis.opsForStream().delete(MqKeys.dlq(topic), exactId);
        audit("mq.dlq_replayed", envelope, exactId);
        return true;
    }

    /** 清空死信（DEL 整键）：重建后 XLEN 自然归 0。@return 键存在且已删除。 */
    public boolean clear(String topic) {
        requireTopic(topic);
        Boolean deleted = redis.delete(MqKeys.dlq(topic));
        boolean ok = Boolean.TRUE.equals(deleted);
        if (ok && audit != null) {
            audit.audit("mq.dlq_cleared", topic, null, null, "DLQ", "CLEARED", "message", null);
        }
        return ok;
    }

    private void audit(String action, EventEnvelope envelope, String entryId) {
        if (audit != null) {
            audit.audit(action, envelope.msgId(), null, null, "DLQ", "REPLAYED", "message", entryId);
        }
    }

    private static void requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
    }

    private DlqEntry toEntry(String entryId, Map<String, String> fields) {
        return new DlqEntry(entryId,
                fields.get("msgId"), fields.get("topic"), fields.get("eventType"), fields.get("bizNo"),
                fields.get("traceId"), fields.get("producer"), fields.get("occurredAt"),
                fields.get("dlqReason"), fields.get("payload"));
    }

    private Map<String, String> toStringMap(Map<Object, Object> raw) {
        Map<String, String> m = new LinkedHashMap<>();
        raw.forEach((k, v) -> m.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return m;
    }
}
