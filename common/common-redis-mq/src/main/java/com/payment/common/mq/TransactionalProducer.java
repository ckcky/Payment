package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 事务消息生产者（spec 029 / T13-T16 / FR-102~104、FR-201）。
 *
 * <p><b>协议（INV-3，先事务后可见）</b>：</p>
 * <ol>
 *   <li>{@code prepare()}：把信封写 {@code mq:half:{topic}:{msgId}} + {@code ZADD mq:half:idx}
 *       ——此时消息**不可见**（不在 {@code mq:stream:*}）</li>
 *   <li>业务本地事务（DB 落库）</li>
 *   <li>{@code commit()}：{@code XADD mq:stream:{topic}} + 清半消息与索引 → 消息可见</li>
 * </ol>
 *
 * <p>事务回滚则调 {@code rollback()}，只清半消息、不投递。若进程在第 2 步后、第 3 步前崩溃，
 * 半消息仍在，由 {@link HalfMessageScanner} 回查真相表补投或丢弃。</p>
 *
 * <p><b>推荐入口 {@code sendInTransaction}</b>：把「prepare → 事务 → commit/rollback」收敛为
 * 模板方法，调用点不必手写 try/catch（漏写即消息泄漏）。</p>
 */
public class TransactionalProducer {

    private static final Logger log = LoggerFactory.getLogger(TransactionalProducer.class);

    private final StringRedisTemplate redis;
    private final MqProperties props;
    private final BusinessMetrics metrics;
    private final String producerName;
    /** FR-503 / T56：投递结果审计（可空）。 */
    private final StructuredAuditLogger audit;

    public TransactionalProducer(StringRedisTemplate redis, MqProperties props,
                                 BusinessMetrics metrics, String producerName) {
        this(redis, props, metrics, producerName, null);
    }

    public TransactionalProducer(StringRedisTemplate redis, MqProperties props,
                                 BusinessMetrics metrics, String producerName,
                                 StructuredAuditLogger audit) {
        this.redis = redis;
        this.props = props;
        this.metrics = metrics;
        this.producerName = producerName;
        this.audit = audit;
    }

    /**
     * FR-503：投递结果审计（action = mq.committed / mq.rolled_back）；
     * traceId 由 {@link StructuredAuditLogger} 从上下文自动带上。
     * 以 entityType=message、entityId=msgId 承载，amountMinor 传 null（非资金动作）。
     */
    private void audit(String action, EventEnvelope e, String toStatus) {
        if (audit != null) {
            audit.audit(action, e.bizNo(), null, null, e.topic(), toStatus,
                    "message", e.msgId());
        }
    }

    /**
     * 构造信封（自动补 msgId / topic / eventType / traceId / producer / occurredAt）。
     *
     * @param topic    逻辑通道名
     * @param bizNo    业务单号（INV-6）
     * @param payload  业务负载（由生产方从自己的库富化，INV-4）
     */
    public EventEnvelope envelope(String topic, String bizNo, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                topic,
                topic,
                bizNo,
                // FR-601：从当前上下文取 traceId（无则新建），使异步链路可串（INV-9）
                TraceContext.getOrCreate(),
                producerName,
                Instant.now(),
                payload == null ? Map.of() : Map.copyOf(payload));
    }

    /**
     * 写半消息 + 回查索引（FR-102）。此步**必须**在本地事务之前调用（INV-3）。
     *
     * @return 信封本身（便于链式调用 commit/rollback）
     */
    public EventEnvelope prepare(EventEnvelope envelope) {
        try {
            Map<String, String> fields = toMap(envelope);
            redis.opsForHash().putAll(MqKeys.half(envelope.topic(), envelope.msgId()), fields);
            // ZSet 索引：score = prepare 时刻毫秒，供扫描器按「准备超时」摘取
            redis.opsForZSet().add(MqKeys.HALF_IDX,
                    MqKeys.halfIdxMember(envelope.topic(), envelope.msgId()),
                    System.currentTimeMillis());
            count("mq.prepared", envelope.topic());
            log.debug("MQ prepare topic={} msgId={} bizNo={} traceId={}",
                    envelope.topic(), envelope.msgId(), envelope.bizNo(), envelope.traceId());
            return envelope;
        } catch (RuntimeException e) {
            // prepare 失败即整个事务应回滚（半消息都没写成功，投递无从谈起）
            throw new MqException("MQ prepare 失败 [topic=" + envelope.topic() + ", msgId=" + envelope.msgId() + "]", e);
        }
    }

    /**
     * 提交可见（FR-103）：XADD 到可见 Stream + 清半消息与索引。此步在本地事务提交之后调用。
     */
    public void commit(EventEnvelope envelope) {
        try {
            String[] fields = EnvelopeCodec.toFields(envelope);
            Map<String, String> fv = new HashMap<>();
            for (int i = 0; i < fields.length; i += 2) {
                fv.put(fields[i], fields[i + 1]);
            }
            redis.opsForStream().add(MqKeys.stream(envelope.topic()), fv);
            cleanup(envelope);
            count("mq.committed", envelope.topic());
            audit("mq.committed", envelope, "VISIBLE");
            log.info("MQ commit topic={} msgId={} bizNo={} traceId={}",
                    envelope.topic(), envelope.msgId(), envelope.bizNo(), envelope.traceId());
        } catch (RuntimeException e) {
            // commit 失败不回滚本地事务（INV-1：事实不回滚）；半消息仍在，扫描器会回查补投
            log.error("MQ commit 失败，依赖回查补投 [topic={}, msgId={}]", envelope.topic(), envelope.msgId(), e);
            throw new MqException("MQ commit 失败 [topic=" + envelope.topic() + ", msgId=" + envelope.msgId() + "]", e);
        }
    }

    /** 回滚（FR-104）：只清半消息与索引，不投递。 */
    public void rollback(EventEnvelope envelope) {
        try {
            cleanup(envelope);
            count("mq.rolled_back", envelope.topic());
            audit("mq.rolled_back", envelope, "DISCARDED");
            log.info("MQ rollback topic={} msgId={} bizNo={}（本地事务未提交，消息不投递）",
                    envelope.topic(), envelope.msgId(), envelope.bizNo());
        } catch (RuntimeException e) {
            // 清理失败不抛：半消息留在原地会被扫描器回查，真相表判定 ROLLBACK 后同样清掉
            log.warn("MQ rollback 清理失败（扫描器兜底）[topic={}, msgId={}]", envelope.topic(), envelope.msgId(), e);
        }
    }

    /**
     * 模板方法（T16）：收敛 prepare → 本地事务 → commit/rollback（FR-103/104 的调用纪律）。
     *
     * <p>调用点只需把「本地事务」写进 {@code txAction}，事务正常返回即 commit、抛异常即 rollback。
     * 这样避免每个调用点各写一遍 try/catch 而漏掉某条路径。</p>
     *
     * @param topic    逻辑通道名
     * @param bizNo    业务单号
     * @param payload  业务负载
     * @param txAction 本地事务（通常为 repository 落库）
     * @param <T>      事务返回值类型
     * @return 事务返回值
     */
    public <T> T sendInTransaction(String topic, String bizNo, Map<String, Object> payload, Supplier<T> txAction) {
        EventEnvelope envelope = envelope(topic, bizNo, payload);
        prepare(envelope);
        T result;
        try {
            result = txAction.get();
        } catch (RuntimeException e) {
            rollback(envelope);
            throw e;
        }
        commit(envelope);
        return result;
    }

    /** 记录 mq.prepared / mq.committed / mq.rolled_back 指标（FR-502，按 topic 打标签）。 */
    public void count(String metric, String topic) {
        if (metrics != null) {
            metrics.counter(metric, 1, "topic", topic);
        }
    }

    /** 供半消息扫描器复用：清理半消息 + 索引。 */
    void cleanup(EventEnvelope envelope) {
        redis.delete(MqKeys.half(envelope.topic(), envelope.msgId()));
        redis.opsForZSet().remove(MqKeys.HALF_IDX, MqKeys.halfIdxMember(envelope.topic(), envelope.msgId()));
    }

    /** 供半消息扫描器读回半消息信封。 */
    EventEnvelope readHalf(String topic, String msgId) {
        Map<Object, Object> raw = redis.opsForHash().entries(MqKeys.half(topic, msgId));
        if (raw.isEmpty()) {
            return null;
        }
        Map<String, String> f = new HashMap<>();
        raw.forEach((k, v) -> f.put(String.valueOf(k), String.valueOf(v)));
        try {
            return EnvelopeCodec.fromFields(f);
        } catch (Exception e) {
            log.warn("半消息反序列化失败 [topic={}, msgId={}]", topic, msgId, e);
            return null;
        }
    }

    /** 扫描器用：取 score 早于 cutoff 的半消息索引 member（{@code topic:msgId}）。 */
    Set<String> dueHalfMessages(long cutoffMillis) {
        Set<String> members = redis.opsForZSet().rangeByScore(MqKeys.HALF_IDX, 0, cutoffMillis);
        return members == null ? Set.of() : members;
    }

    /** 扫描器用：清理残留索引（半消息已不存在时）。 */
    void removeHalfIndex(String topic, String msgId) {
        redis.opsForZSet().remove(MqKeys.HALF_IDX, MqKeys.halfIdxMember(topic, msgId));
    }

    /** 扫描器用：UNKNOWN 累计回查次数 +1，返回累计值。 */
    int incrementCheckCount(String topic, String msgId) {
        String key = MqKeys.HALF_CHECKS + MqKeys.halfIdxMember(topic, msgId);
        Long n = redis.opsForValue().increment(key);
        // 计数键设 TTL，避免历史键永久堆积（回查场景分钟级；1h 足够覆盖）
        redis.expire(key, Duration.ofHours(1));
        return n == null ? 1 : n.intValue();
    }

    /** 扫描器用：清除回查计数。 */
    void clearCheckCount(String topic, String msgId) {
        redis.delete(MqKeys.HALF_CHECKS + MqKeys.halfIdxMember(topic, msgId));
    }

    /** 扫描器用：投递到死信队列 + 指标（FR-108）。 */
    void toDlq(EventEnvelope envelope, String reason) {
        try {
            Map<String, String> f = toMap(envelope);
            f.put("dlqReason", reason);
            redis.opsForStream().add(MqKeys.dlq(envelope.topic()), f);
            count("mq.dead_letter", envelope.topic());
        } catch (RuntimeException e) {
            log.error("投递 DLQ 失败 [topic={}, msgId={}]", envelope.topic(), envelope.msgId(), e);
        }
    }

    private Map<String, String> toMap(EventEnvelope e) {
        try {
            String[] fields = EnvelopeCodec.toFields(e);
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < fields.length; i += 2) {
                m.put(fields[i], fields[i + 1]);
            }
            return m;
        } catch (Exception ex) {
            throw new MqException("MQ 信封序列化失败 [topic=" + e.topic() + "]", ex);
        }
    }
}
