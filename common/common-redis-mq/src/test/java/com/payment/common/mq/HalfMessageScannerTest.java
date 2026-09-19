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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 半消息回查扫描器测试（spec 029 / T26 / FR-105~106）。
 *
 * <p>覆盖回查三种状态分派：COMMIT 补投、ROLLBACK 丢弃、UNKNOWN 超次进 DLQ。</p>
 */
class HalfMessageScannerTest {

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
    @DisplayName("T26-1 回查 COMMIT → 补投到可见队列")
    void commitStateRepublishes() {
        EventEnvelope e = producer.envelope("order.paid", "ORD-1", Map.of("orderNo", "ORD-1"));
        producer.prepare(e);
        // 直接把索引 score 拨回过去，使扫描器判定「已超时」
        redis.opsForZSet().add(MqKeys.HALF_IDX, MqKeys.halfIdxMember("order.paid", e.msgId()), 0);

        HalfMessageScanner scanner = new HalfMessageScanner(producer, props, new NoOpMetrics(),
                List.of(new FixedChecker(TransactionChecker.LocalTxState.COMMIT)));
        scanner.scan();

        assertThat(redis.opsForStream().size(MqKeys.stream("order.paid")))
                .as("COMMIT → 补投").isEqualTo(1L);
        assertThat(redis.hasKey(MqKeys.half("order.paid", e.msgId()))).isFalse();
    }

    @Test
    @DisplayName("T26-2 回查 ROLLBACK → 不投递且清半消息")
    void rollbackStateDiscards() {
        EventEnvelope e = producer.envelope("order.paid", "ORD-2", Map.of("orderNo", "ORD-2"));
        producer.prepare(e);
        redis.opsForZSet().add(MqKeys.HALF_IDX, MqKeys.halfIdxMember("order.paid", e.msgId()), 0);

        HalfMessageScanner scanner = new HalfMessageScanner(producer, props, new NoOpMetrics(),
                List.of(new FixedChecker(TransactionChecker.LocalTxState.ROLLBACK)));
        scanner.scan();

        Long size = redis.opsForStream().size(MqKeys.stream("order.paid"));
        assertThat(size == null ? 0L : size).as("ROLLBACK → 不投递").isZero();
        assertThat(redis.hasKey(MqKeys.half("order.paid", e.msgId()))).isFalse();
    }

    @Test
    @DisplayName("T26-3 回查 UNKNOWN 超次 → 进 DLQ")
    void unknownStateGoesToDlqAfterMaxChecks() {
        props.setMaxCheckTimes(2);
        EventEnvelope e = producer.envelope("order.paid", "ORD-3", Map.of("orderNo", "ORD-3"));
        producer.prepare(e);
        redis.opsForZSet().add(MqKeys.HALF_IDX, MqKeys.halfIdxMember("order.paid", e.msgId()), 0);

        HalfMessageScanner scanner = new HalfMessageScanner(producer, props, new NoOpMetrics(),
                List.of(new FixedChecker(TransactionChecker.LocalTxState.UNKNOWN)));
        scanner.scan();   // 第 1 次 UNKNOWN
        scanner.scan();   // 第 2 次 UNKNOWN → 达上限进 DLQ

        assertThat(redis.opsForStream().size(MqKeys.dlq("order.paid")))
                .as("UNKNOWN 超次 → DLQ").isEqualTo(1L);
    }

    @Test
    @DisplayName("T26-4 无 checker 认领时按 UNKNOWN 处理（不误投）")
    void noCheckerMeansUnknown() {
        EventEnvelope e = producer.envelope("order.paid", "ORD-4", Map.of("orderNo", "ORD-4"));
        producer.prepare(e);
        redis.opsForZSet().add(MqKeys.HALF_IDX, MqKeys.halfIdxMember("order.paid", e.msgId()), 0);

        HalfMessageScanner scanner = new HalfMessageScanner(producer, props, new NoOpMetrics(), List.of());
        scanner.scan();

        Long size = redis.opsForStream().size(MqKeys.stream("order.paid"));
        assertThat(size == null ? 0L : size).as("无 checker → 不投递").isZero();
        // 半消息仍在（等待下一轮或人工处置）
        assertThat(redis.hasKey(MqKeys.half("order.paid", e.msgId()))).isTrue();
    }

    /** 固定返回指定状态的 checker（仅认领 order.paid）。 */
    private static final class FixedChecker implements TransactionChecker {
        private final LocalTxState state;

        FixedChecker(LocalTxState state) {
            this.state = state;
        }

        @Override
        public boolean supports(EventEnvelope envelope) {
            return "order.paid".equals(envelope.topic());
        }

        @Override
        public LocalTxState check(EventEnvelope envelope) {
            return state;
        }
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
