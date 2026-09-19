package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.trace.TraceContext;
import com.payment.common.core.trace.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stream 消费端（spec 029 / T20-T23 / FR-107~109、FR-602、FR-604）。
 *
 * <p>消费循环：{@code XAUTOCLAIM} 接管僵尸消息 → {@code XREADGROUP} 拉新消息 → 逐条处理，
 * 成功 {@code XACK}，失败按 1s/2s/4s 退避重试，超 {@code maxRetry} 进 DLQ 后 {@code XACK}。</p>
 *
 * <p><b>FR-602</b>：处理前把 {@code envelope.traceId()} 放回 MDC，{@code finally} 清理，
 * 使消费逻辑内部日志自动带上原链路 traceId。</p>
 *
 * <p><b>FR-604</b>：{@code handler} 内若再生产新事件，{@code TransactionalProducer.envelope()}
 * 会从 {@link TraceContext} 取当前 traceId——即自动继承本消费的 traceId，链路延续。</p>
 */
public class StreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    private final StringRedisTemplate redis;
    private final MqProperties props;
    private final BusinessMetrics metrics;
    private final String topic;
    private final String group;
    private final String consumerName;
    private final RouteHandler handler;
    private final TransactionalProducer producer;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running = false;

    /** 消费处理器：路由到具体业务订阅方。 */
    @FunctionalInterface
    public interface RouteHandler {
        void handle(EventEnvelope envelope);
    }

    public StreamConsumer(StringRedisTemplate redis, MqProperties props, BusinessMetrics metrics,
                          String topic, String group, String consumerName,
                          RouteHandler handler, TransactionalProducer producer) {
        this.redis = redis;
        this.props = props;
        this.metrics = metrics;
        this.topic = topic;
        this.group = group;
        this.consumerName = consumerName;
        this.handler = handler;
        this.producer = producer;
    }

    /** T20：启动时建组（MKSTREAM，忽略 BUSYGROUP）。 */
    public void ensureGroup() {
        String stream = MqKeys.stream(topic);
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("MQ 建消费组 [stream={}, group={}]", stream, group);
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("BUSYGROUP")) {
                log.debug("MQ 消费组已存在 [stream={}, group={}]", stream, group);
            } else if (msg.contains("no such key")) {
                // Stream 不存在：用 MKSTREAM 建空流 + 组
                try {
                    redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
                } catch (RuntimeException ex) {
                    log.warn("MQ 建组失败（流不存在）[stream={}]", stream, ex);
                }
            } else {
                log.warn("MQ 建组异常 [stream={}, group={}]", stream, group, e);
            }
        }
    }

    /** 启动消费循环（由装配层提交到独立线程）。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running = true;
        ensureGroup();
        log.info("MQ 消费者启动 [topic={}, group={}, consumer={}]", topic, group, consumerName);
        while (running) {
            try {
                pollOnce();
            } catch (RuntimeException e) {
                log.warn("MQ 消费循环异常（1s 后重试）[topic={}, group={}]", topic, group, e);
                sleep(Duration.ofSeconds(1));
            }
        }
    }

    public void stop() {
        running = false;
    }

    /**
     * 一轮拉取：XAUTOCLAIM 接管 + XREADGROUP 新消息（FR-107、FR-109）。
     */
    void pollOnce() {
        // T22 / FR-109：先认领闲置超 minIdleMs 的 PEL 消息（同组实例接管崩溃消费者）
        claimStale();
        // T21：XREADGROUP 阻塞拉取
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(props.getBatchSize()).block(props.getBlockMs()),                StreamOffset.create(MqKeys.stream(topic), ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record.getId(), toStringMap(record.getValue()));
        }
    }

    /** T22：XAUTOCLAIM 接管闲置消息。 */
    private void claimStale() {
        try {
            redis.opsForStream().claim(MqKeys.stream(topic), group, consumerName, props.getMinIdleMs(),
                    RecordId.of("0-0"));
        } catch (RuntimeException e) {
            // 认领失败不影响本轮回合（如 PEL 为空时部分实现会抛）
            log.debug("MQ XAUTOCLAIM 无接管或失败 [topic={}, group={}]", topic, group);
        }
    }

    /**
     * T23：处理单条消息——成功 XACK；失败退避重试 1s/2s/4s，超 maxRetry 进 DLQ + XACK。
     */
    void processRecord(RecordId id, Map<String, String> fields) {
        EventEnvelope envelope;
        try {
            envelope = EnvelopeCodec.fromFields(fields);
        } catch (RuntimeException e) {
            log.error("MQ 消息反序列化失败，进 DLQ [topic={}, id={}]", topic, id, e);
            ack(id);
            return;
        }
        // FR-602：恢复 traceId 进 MDC（finally 清理），消费逻辑日志自动带原链路
        String prevTrace = TraceContext.getTraceId();
        String prevMdcTrace = MDC.get(TraceIdFilter.MDC_KEY);
        String prevBizNo = MDC.get("bizNo");
        int attempt = 0;
        int maxRetry = props.getMaxRetry();
        while (true) {
            attempt++;
            try {
                TraceContext.setTraceId(envelope.traceId());
                MDC.put(TraceIdFilter.MDC_KEY, nz(envelope.traceId()));
                MDC.put("bizNo", nz(envelope.bizNo()));
                handler.handle(envelope);
                if (metrics != null) {
                    metrics.counter("mq.consumed", 1, "topic", topic, "group", group);
                }
                ack(id);
                return;
            } catch (RuntimeException e) {
                if (attempt > maxRetry) {
                    log.error("MQ 消费重试 {} 次仍失败 → DLQ [topic={}, id={}, msgId={}, bizNo={}]",
                            maxRetry, topic, id, envelope.msgId(), envelope.bizNo(), e);
                    toDlq(envelope, "消费重试超限: " + e.getMessage());
                    ack(id);
                    if (metrics != null) {
                        metrics.counter("mq.dead_letter", 1, "topic", topic, "group", group);
                    }
                    return;
                }
                long backoff = props.getRetryBackoffBase().toMillis() * (1L << (attempt - 1));
                log.warn("MQ 消费失败第 {} 次，{}ms 后退避重试 [topic={}, id={}]", attempt, backoff, topic, id, e);
                if (metrics != null) {
                    metrics.counter("mq.retried", 1, "topic", topic, "group", group);
                }
                sleep(Duration.ofMillis(backoff));
            } finally {
                restoreMdc(prevTrace, prevMdcTrace, prevBizNo);
            }
        }
    }

    private void toDlq(EventEnvelope envelope, String reason) {
        if (producer != null) {
            producer.toDlq(envelope, reason);
        }
    }

    private void ack(RecordId id) {
        try {
            redis.opsForStream().acknowledge(MqKeys.stream(topic), group, id);
        } catch (RuntimeException e) {
            log.warn("MQ XACK 失败 [topic={}, id={}]", topic, id, e);
        }
    }

    private void restoreMdc(String prevTrace, String prevMdcTrace, String prevBizNo) {
        if (prevTrace != null) {
            TraceContext.setTraceId(prevTrace);
        } else {
            TraceContext.clear();
        }
        if (prevMdcTrace != null) {
            MDC.put(TraceIdFilter.MDC_KEY, prevMdcTrace);
        } else {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
        if (prevBizNo != null) {
            MDC.put("bizNo", prevBizNo);
        } else {
            MDC.remove("bizNo");
        }
    }

    private Map<String, String> toStringMap(Map<Object, Object> raw) {
        Map<String, String> m = new java.util.HashMap<>();
        raw.forEach((k, v) -> m.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
