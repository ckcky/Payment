package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务生产者语义测试（spec 029 / T25 / INV-3）。
 *
 * <p>断言核心协议：prepare 后消息**不可见**；commit 后可见；rollback 后不可见且半消息被清。</p>
 */
class TransactionalProducerTest {

    private RedisServer redisServer;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private TransactionalProducer producer;
    private MqProperties props;

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
        producer = new TransactionalProducer(redis, props, new NoOpMetrics(), "test-producer");
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
    @DisplayName("T25-1 prepare 后消息不可见（不在 stream，只在 half）")
    void prepareMakesMessageInvisible() {
        EventEnvelope e = producer.envelope("order.paid", "ORD-1", Map.of("orderNo", "ORD-1"));
        producer.prepare(e);

        // 可见 Stream 无此消息
        Long streamSize = redis.opsForStream().size(MqKeys.stream("order.paid"));
        assertThat(streamSize == null ? 0L : streamSize)
                .as("prepare 后可见队列应为空").isZero();
        // 半消息 Hash 存在
        assertThat(redis.hasKey(MqKeys.half("order.paid", e.msgId())))
                .as("半消息应写入 mq:half").isTrue();
        // 回查索引存在
        assertThat(redis.opsForZSet().score(MqKeys.HALF_IDX,
                MqKeys.halfIdxMember("order.paid", e.msgId())))
                .as("回查索引应有该条目").isNotNull();
    }

    @Test
    @DisplayName("T25-2 prepare→commit 后消息可见且半消息被清")
    void commitMakesMessageVisible() {
        EventEnvelope e = producer.envelope("order.paid", "ORD-2", Map.of("orderNo", "ORD-2"));
        producer.prepare(e);
        producer.commit(e);

        Long streamSize = redis.opsForStream().size(MqKeys.stream("order.paid"));
        assertThat(streamSize).as("commit 后可见队列应有 1 条").isEqualTo(1L);
        assertThat(redis.hasKey(MqKeys.half("order.paid", e.msgId())))
                .as("半消息应被清理").isFalse();
        assertThat(redis.opsForZSet().score(MqKeys.HALF_IDX,
                MqKeys.halfIdxMember("order.paid", e.msgId())))
                .as("回查索引应被清理").isNull();
    }

    @Test
    @DisplayName("T25-3 prepare→rollback 后消息不可见且半消息被清（INV-3）")
    void rollbackKeepsMessageInvisible() {
        EventEnvelope e = producer.envelope("order.paid", "ORD-3", Map.of("orderNo", "ORD-3"));
        producer.prepare(e);
        producer.rollback(e);

        Long streamSize = redis.opsForStream().size(MqKeys.stream("order.paid"));
        assertThat(streamSize == null ? 0L : streamSize)
                .as("rollback 后可见队列应为空").isZero();
        assertThat(redis.hasKey(MqKeys.half("order.paid", e.msgId())))
                .as("半消息应被清理").isFalse();
    }

    @Test
    @DisplayName("T25-4 sendInTransaction 事务异常 → rollback，消息不投递")
    void sendInTransactionRollsBackOnException() {
        try {
            producer.sendInTransaction("refund.result", "REF-1", Map.of("refundNo", "REF-1"), () -> {
                throw new IllegalStateException("模拟本地事务失败");
            });
        } catch (IllegalStateException expected) {
            // 预期：事务异常向上冒泡
        }
        Long streamSize = redis.opsForStream().size(MqKeys.stream("refund.result"));
        assertThat(streamSize == null ? 0L : streamSize)
                .as("本地事务失败 → 消息零投递").isZero();
    }

    @Test
    @DisplayName("T25-5 sendInTransaction 事务成功 → commit，消息投递")
    void sendInTransactionCommitsOnSuccess() {
        String r = producer.sendInTransaction("refund.result", "REF-2", Map.of("refundNo", "REF-2"),
                () -> "tx-done");
        assertThat(r).isEqualTo("tx-done");
        Long streamSize = redis.opsForStream().size(MqKeys.stream("refund.result"));
        assertThat(streamSize).as("本地事务成功 → 消息投递").isEqualTo(1L);
    }

    @Test
    @DisplayName("T25-6 信封 traceId 从当前上下文继承（FR-601）")
    void envelopeInheritsTraceId() {
        com.payment.common.core.trace.TraceContext.setTraceId("trace-abc");
        try {
            EventEnvelope e = producer.envelope("order.paid", "ORD-4", Map.of());
            assertThat(e.traceId()).isEqualTo("trace-abc");
        } finally {
            com.payment.common.core.trace.TraceContext.clear();
        }
    }

    @Test
    @DisplayName("T25-7 信封序列化往返保真（含 payload）")
    void envelopeRoundTrip() {
        EventEnvelope e = new EventEnvelope("m1", "order.paid", "order.paid", "ORD-5",
                "t1", "order-service", java.time.Instant.parse("2026-09-19T00:00:00Z"),
                Map.of("orderNo", "ORD-5", "amount", 2500));
        EventEnvelope back = EnvelopeCodec.fromFields(toMap(EnvelopeCodec.toFields(e)));
        assertThat(back.msgId()).isEqualTo("m1");
        assertThat(back.bizNo()).isEqualTo("ORD-5");
        assertThat(back.traceId()).isEqualTo("t1");
        assertThat(back.num("amount")).isEqualTo(2500L);
    }

    private static Map<String, String> toMap(String[] fv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < fv.length; i += 2) {
            m.put(fv[i], fv[i + 1]);
        }
        return m;
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
