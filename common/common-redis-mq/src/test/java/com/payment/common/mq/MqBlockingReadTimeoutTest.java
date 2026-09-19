package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阻塞读与客户端超时错配的回归测试（spec 029 修复）。
 *
 * <p><b>背景</b>：{@link StreamConsumer} 用 {@code XREADGROUP ... BLOCK blockMs} 阻塞拉取。
 * 若 Redis 客户端命令超时 ≤ {@code blockMs}，则每轮读取都会在服务端正常阻塞期间被客户端判超时，
 * 抛 {@code RedisCommandTimeoutException}；消费循环每 1s 空转重试，**永远读不到消息**。
 * 真实表现是「支付成功但订单永不收敛为 PAID」，且无任何业务异常日志。</p>
 *
 * <p><b>为什么既有单测漏掉</b>：{@code StreamConsumerTest} 用
 * {@code new LettuceConnectionFactory(host, port)}（Lettuce 默认 60s 超时），
 * 2000ms 阻塞读天然成立。本测试显式构造「1s 命令超时 + 2s 阻塞」的错配，锁定缺陷。</p>
 */
class MqBlockingReadTimeoutTest {

    private RedisServer redisServer;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private TransactionalProducer producer;
    private MqProperties props;
    private static final String TOPIC = "order.paid";

    private void startRedis() throws IOException {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();
        factory = newTimeoutFactory(port, Duration.ofSeconds(5), Duration.ofSeconds(1));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        props = new MqProperties();
        producer = new TransactionalProducer(redis, props, new NoOpMetrics(), "test");
    }

    /**
     * 构造带显式命令超时的连接工厂——复刻服务端 YAML 里
     * {@code spring.data.redis.timeout} 的语义。
     */
    private static LettuceConnectionFactory newTimeoutFactory(int port, Duration commandTimeout,
                                                             Duration connectTimeout) {
        LettuceClientConfiguration cfg = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(io.lettuce.core.ClientOptions.builder()
                        .socketOptions(io.lettuce.core.SocketOptions.builder()
                                .connectTimeout(connectTimeout)
                                .build())
                        .build())
                .build();
        return new LettuceConnectionFactory(new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", port), cfg);
    }

    @BeforeEach
    void setUp() throws IOException {
        startRedis();
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
    @DisplayName("回归：客户端超时(1s) < 阻塞时长(2s) → 空流 XREADGROUP 必超时（CI 挂死根因）")
    void blockingReadExceedingClientTimeoutAlwaysFails() throws IOException {
        // 客户端超时 1s，而 blockMs=2000ms（MqProperties 默认）——生产环境各服务 YAML 的原值
        assertThat(props.getBlockMs()).as("默认阻塞时长 2000ms").isEqualTo(Duration.ofMillis(2000));

        // 只有「流上暂无新消息」时 XREADGROUP 才真正进入 BLOCK 等待，也才会撞上
        // 「阻塞时长 > 客户端超时」。这正是缺陷隐蔽之处：有积压消息时读取立即返回、看似正常，
        // 一旦消费追上位点（空流）即开始每轮超时，此后永远读不到新消息。
        factory.destroy();
        redisServer.stop();
        rewireWithTimeout(Duration.ofSeconds(1));

        StreamConsumer consumer = new StreamConsumer(
                redis, props, new NoOpMetrics(), TOPIC, "gx", "cx", env -> { }, producer);
        consumer.ensureGroup();

        assertThatThrownBy(consumer::pollOnce)
                .as("客户端超时 < 阻塞时长 → 空流阻塞读被判超时")
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    @Test
    @DisplayName("回归：客户端超时 == 阻塞时长 同样超时 → 必须有安全余量")
    void equalTimeoutStillFailsSoMarginIsRequired() throws IOException {
        factory.destroy();
        redisServer.stop();
        rewireWithTimeout(Duration.ofSeconds(2));   // 恰好等于 blockMs=2000ms

        StreamConsumer consumer = new StreamConsumer(
                redis, props, new NoOpMetrics(), TOPIC, "geq", "ceq", env -> { }, producer);
        consumer.ensureGroup();

        // 实测：超时恰好等于阻塞时长时仍在边界处判超时（elapsed≈2008ms）。
        // 因此守卫要求「超时 > 阻塞时长 + 最小余量」，而非仅仅「>=」。
        assertThatThrownBy(consumer::pollOnce)
                .as("超时等于阻塞时长 → 边界处仍超时")
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    @Test
    @DisplayName("修复后：客户端超时(5s) > 阻塞时长(2s) → 空流阻塞读正常返回（消费端可空转待新消息）")
    void blockingReadWithinClientTimeoutSucceeds() throws IOException {
        factory.destroy();
        redisServer.stop();
        rewireWithTimeout(Duration.ofSeconds(5));   // 复刻修复后的 spring.data.redis.timeout=5000ms

        StreamConsumer consumer = new StreamConsumer(
                redis, props, new NoOpMetrics(), TOPIC, "gok", "cok", env -> { }, producer);
        consumer.ensureGroup();

        long start = System.currentTimeMillis();
        // 空流上阻塞读应「正常返回空结果」（阻塞满 blockMs 后返回），而不是抛异常
        consumer.pollOnce();
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
                .as("确实阻塞了接近 blockMs(2000ms) 后正常返回；若立即返回说明未真正进入阻塞")
                .isGreaterThanOrEqualTo(1800L);

        // 投递新消息后同一消费组可正常读到并处理（这正是生产环境缺失的能力）
        EventEnvelope e = producer.envelope(TOPIC, "ORD-OK", Map.of("orderNo", "ORD-OK"));
        producer.prepare(e);
        producer.commit(e);
        AtomicInteger handled = new AtomicInteger();
        StreamConsumer c2 = new StreamConsumer(
                redis, props, new NoOpMetrics(), TOPIC, "gok", "cok",
                env -> handled.incrementAndGet(), producer);
        c2.pollOnce();
        assertThat(handled.get()).as("修复后新消息被正常消费").isEqualTo(1);
    }

    /** 重建连接工厂为指定命令超时（模拟服务端 YAML 的 spring.data.redis.timeout）。 */
    private void rewireWithTimeout(Duration commandTimeout) throws IOException {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();
        factory = newTimeoutFactory(port, commandTimeout, Duration.ofSeconds(1));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        producer = new TransactionalProducer(redis, props, new NoOpMetrics(), "test");
    }

    @Test
    @DisplayName("守卫：redis.timeout ≤ block-ms 时启动期 fail-fast")
    void guardRejectsMisconfiguration() {
        MqProperties tooShort = new MqProperties();
        tooShort.setBlockMs(Duration.ofSeconds(2));

        assertThatThrownBy(() -> new MqTimeoutGuard(tooShort, Duration.ofSeconds(1)).afterPropertiesSet())
                .as("1s 超时 + 2s 阻塞 → 启动即失败")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("block-ms");

        // 合法配置不抛
        new MqTimeoutGuard(tooShort, Duration.ofSeconds(5)).afterPropertiesSet();
        new MqTimeoutGuard(tooShort, Duration.ofSeconds(60)).afterPropertiesSet();
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
