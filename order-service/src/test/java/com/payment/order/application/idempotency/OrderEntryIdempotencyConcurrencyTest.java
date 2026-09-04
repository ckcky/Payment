package com.payment.order.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.order.api.dto.CreateOrderResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

/**
 * 下单入口幂等并发量化测试（R3 🟡#2）：以真实 Redis（embedded，in-JVM 无需 Docker）模拟
 * 同一 {@code Idempotency-Key} 的洪峰重复请求，断言「恰好 1 个 PROCEED、其余全部 CONFLICT」，
 * 即并发重复不会造成重复创建订单；并串联校验完整生命周期（IN_PROGRESS → DONE → REPLAY）。
 */
class OrderEntryIdempotencyConcurrencyTest {

    private static final String SAME_KEY = "concurrent-key-001";

    private RedisServer redisServer;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private OrderEntryIdempotencyService service;

    @BeforeEach
    void setUp() throws IOException {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();

        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        service = new OrderEntryIdempotencyService(redis, new com.fasterxml.jackson.databind.ObjectMapper(),
                new NoOpBusinessMetrics());
    }

    @AfterEach
    void tearDown() {
        try {
            if (factory != null) {
                factory.destroy();
            }
        } catch (Exception ignored) {
        }
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("同 key 50 并发：恰好 1 个 PROCEED、49 个 CONFLICT，不重复创建")
    void concurrentDuplicateYieldsExactlyOneProceed() throws Exception {
        final int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger proceed = new AtomicInteger(0);
        AtomicInteger conflict = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IdempotencyDecision d = service.check(SAME_KEY);
                    if (d.isProceed()) {
                        proceed.incrementAndGet();
                    } else if (d.isConflict()) {
                        conflict.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 并发重复的核心不变量：至多一个请求进入创建流程，其余被并发冲突拦截
        assertThat(proceed.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        // 胜出线程仅占位 IN_PROGRESS，尚未 complete —— 库存/订单侧不会被重复创建
        assertThat(redis.opsForValue().get("idemp:order:" + SAME_KEY)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("生命周期串联：并发后 complete → 再次 check 返回 REPLAY（携带首次响应）")
    void lifecycleProceedThenCompleteThenReplay() {
        // 前置：单次进入创建流程
        IdempotencyDecision first = service.check(SAME_KEY);
        assertThat(first.isProceed()).isTrue();

        CreateOrderResponse response = new CreateOrderResponse("OR1001", "TX1001", "PAID", 9900L, "CNY", "PM30", "SUCCEEDED");
        service.complete(SAME_KEY, response);

        // 完成后同 key 重放，直接拿到首次响应，不再创建
        IdempotencyDecision replay = service.check(SAME_KEY);
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.storedJson().orElse("")).contains("\"orderNo\":\"OR1001\"");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** 不记指标的 NoOp 实现，用于把算力集中在并发不变量上。 */
    private static final class NoOpBusinessMetrics implements BusinessMetrics {
        @Override
        public void counter(String name, double value, String... tags) {
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
        }
    }
}
