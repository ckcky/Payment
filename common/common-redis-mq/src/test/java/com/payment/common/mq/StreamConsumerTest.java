package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.trace.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stream 消费端测试（spec 029 / T27 / FR-107~108、FR-602）。
 *
 * <p>覆盖：正常消费 XACK、失败退避重试后进 DLQ、消费前恢复 traceId 进 MDC。</p>
 */
class StreamConsumerTest {

    private RedisServer redisServer;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private TransactionalProducer producer;
    private MqProperties props;
    private static final String TOPIC = "order.paid";

    @BeforeEach
    void setUp() throws IOException {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        props = new MqProperties();
        producer = new TransactionalProducer(redis, props, new NoOpMetrics(), "test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (factory != null) {
            factory.destroy();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Test
    @DisplayName("T27-1 正常消费 → XACK 且业务副作用发生一次")
    void consumeAck() {
        // 投递一条消息
        EventEnvelope e = producer.envelope(TOPIC, "ORD-1", Map.of("orderNo", "ORD-1"));
        producer.prepare(e);
        producer.commit(e);

        AtomicInteger handled = new AtomicInteger();
        AtomicReference<String> seenTrace = new AtomicReference<>();
        StreamConsumer consumer = new StreamConsumer(redis, props, new NoOpMetrics(), TOPIC, "g1", "c1",
                env -> {
                    handled.incrementAndGet();
                    seenTrace.set(MDC.get(TraceIdFilter.MDC_KEY));
                }, producer);
        consumer.ensureGroup();
        consumer.pollOnce();

        assertThat(handled.get()).as("消费一次").isEqualTo(1);
        assertThat(seenTrace.get()).as("处理时 MDC 带 traceId（FR-602）").isEqualTo(e.traceId());
        // PEL 应为空（已 XACK）。注意：Spring Data 的 pending() 在 PEL 为空时其 toString
        // 会抛 NoSuchElement，故用 pendingCount 判定而非 isNull。
        assertThat(pendingCount(TOPIC, "g1")).as("已 XACK，PEL 清空").isZero();
    }

    @Test
    @DisplayName("SC-11 消费端恢复 traceId + bizNo 进 MDC，用后清理不串号")
    void consumeRestoresTraceIdAndBizNoThenCleansUp() {
        EventEnvelope e = producer.envelope(TOPIC, "ORD-BIZ-1", Map.of("orderNo", "ORD-BIZ-1"));
        producer.prepare(e);
        producer.commit(e);

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenBiz = new AtomicReference<>();
        StreamConsumer consumer = new StreamConsumer(redis, props, new NoOpMetrics(), TOPIC, "gbiz", "cbiz",
                env -> {
                    seenTrace.set(MDC.get(TraceIdFilter.MDC_KEY));
                    seenBiz.set(MDC.get("bizNo"));
                }, producer);
        consumer.ensureGroup();
        consumer.pollOnce();

        // traceId 跨异步边界连续（FR-601/602）：消费端 MDC 等于信封里的原始 traceId
        assertThat(seenTrace.get()).as("SC-11：消费端 traceId 与生产端一致（跨 MQ 边界连续）")
                .isEqualTo(e.traceId());
        // bizNo 维度（FR-605）：使日志可按业务单号检索
        assertThat(seenBiz.get()).as("SC-11：消费端 MDC 带 bizNo").isEqualTo("ORD-BIZ-1");
        // 用后清理：MDC 不残留，避免线程复用（mq-consumer 单线程循环）把单号带到下一条消息
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).as("消费后 traceId 已清理").isNull();
        assertThat(MDC.get("bizNo")).as("消费后 bizNo 已清理").isNull();
    }

    @Test
    @DisplayName("T27-2 消费抛异常 → 重试 3 次 → 进 DLQ")
    void consumeFailureGoesToDlq() {
        props.setMaxRetry(3);
        props.setRetryBackoffBase(Duration.ofMillis(1));   // 加速测试
        EventEnvelope e = producer.envelope(TOPIC, "ORD-2", Map.of("orderNo", "ORD-2"));
        producer.prepare(e);
        producer.commit(e);

        AtomicInteger attempts = new AtomicInteger();
        StreamConsumer consumer = new StreamConsumer(redis, props, new NoOpMetrics(), TOPIC, "g2", "c2",
                env -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("模拟消费永久失败");
                }, producer);
        consumer.ensureGroup();
        consumer.pollOnce();

        // 初始 1 次 + 重试 3 次 = 4 次
        assertThat(attempts.get()).as("重试次数 = 1 + maxRetry").isEqualTo(4);
        assertThat(redis.opsForStream().size(MqKeys.dlq(TOPIC)))
                .as("超重试 → 进 DLQ").isEqualTo(1L);
        assertThat(pendingCount(TOPIC, "g2")).as("进 DLQ 后 XACK，PEL 清空").isZero();
    }

    @Test
    @DisplayName("T27-3 消费端重复投递同 msgId → 由业务幂等吸收（本层只验证交付两次）")
    void duplicateDeliveryDeliveredTwiceBusinessMustDedup() {
        EventEnvelope e = producer.envelope(TOPIC, "ORD-3", Map.of("orderNo", "ORD-3"));
        producer.prepare(e);
        producer.commit(e);
        // 手工再 XADD 一次同 msgId（模拟重复投递）
        Map<String, String> f = new HashMap<>();
        String[] fv = EnvelopeCodec.toFields(e);
        for (int i = 0; i < fv.length; i += 2) {
            f.put(fv[i], fv[i + 1]);
        }
        redis.opsForStream().add(MqKeys.stream(TOPIC), f);

        AtomicInteger handled = new AtomicInteger();
        StreamConsumer consumer = new StreamConsumer(redis, props, new NoOpMetrics(), TOPIC, "g3", "c3",
                env -> handled.incrementAndGet(), producer);
        consumer.ensureGroup();
        consumer.pollOnce();

        // 通道层 at-least-once：两条都投递；幂等由消费端业务负责（INV-2）
        assertThat(handled.get()).as("重复消息通道层交付两次（消费端需幂等）").isEqualTo(2);
    }

    /** PEL 待确认条数（避免 Spring Data pending() 空 PEL 时 toString 抛异常）。 */
    private long pendingCount(String topic, String group) {
        org.springframework.data.redis.connection.stream.PendingMessagesSummary summary =
                redis.opsForStream().pending(MqKeys.stream(topic), group);
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static final class NoOpMetrics implements BusinessMetrics {
        @Override
        public void counter(String name, double value, String... tags) {
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
        }
    }
}
