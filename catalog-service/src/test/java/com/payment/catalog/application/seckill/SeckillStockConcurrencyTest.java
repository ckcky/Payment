package com.payment.catalog.application.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.BusinessMetrics;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import redis.embedded.RedisServer;

/**
 * 秒杀配额原子预扣并发量化测试（R3 🟡#1）：以真实 Redis（embedded，in-JVM 无需 Docker）对
 * 小配额（10）发动超额并发（50 并发各扣 1），断言「Lua 原子预扣不会超卖」——
 * 恰好 10 个 allowed、40 个 deny，且扣减后剩余恒为 0。
 */
class SeckillStockConcurrencyTest {

    private static final Long SKU = 9001L;

    private RedisServer redisServer;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private SeckillStockService service;

    @BeforeEach
    void setUp() throws IOException {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();

        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        DefaultRedisScript<Long> deductScript = new DefaultRedisScript<>();
        deductScript.setLocation(new ClassPathResource("seckill-deduct.lua"));
        deductScript.setResultType(Long.class);

        SeckillProperties props = new SeckillProperties(); // 默认 enabled=true
        service = new SeckillStockService(redis, deductScript, props, new NoOpBusinessMetrics());
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
    @DisplayName("配额 10、50 并发各扣 1：恰好 10 个 allowed、40 个 deny，无超卖")
    void noOversellUnderConcurrency() throws Exception {
        final long quota = 10;
        final int threads = 50;
        service.seed(SKU, quota);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SeckillResult r = service.tryPreDeduct(SKU, 1);
                    if (r.allowed() && !r.bypassed()) {
                        allowed.incrementAndGet();
                    } else if (!r.allowed()) {
                        denied.incrementAndGet();
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

        // 原子预扣的核心不变量：并发下不多扣、不漏拦
        assertThat(allowed.get()).isEqualTo((int) quota);
        assertThat(denied.get()).isEqualTo(threads - (int) quota);
        // 扣减后剩余恒为 0，证明没有超卖（无负值、无重复扣减）
        String remaining = redis.opsForValue().get("seckill:sku:" + SKU);
        assertThat(remaining).isEqualTo("0");
    }

    @Test
    @DisplayName("rollback 回补配额：扣空后回补 3，剩余恢复为 3")
    void rollbackRestoresQuota() {
        service.seed(SKU, 5);
        service.tryPreDeduct(SKU, 5); // 扣空
        assertThat(redis.opsForValue().get("seckill:sku:" + SKU)).isEqualTo("0");

        service.rollback(SKU, 3); // 订单失败回补
        assertThat(redis.opsForValue().get("seckill:sku:" + SKU)).isEqualTo("3");
    }

    @Test
    @DisplayName("未播种配额（非秒杀 SKU）：返回 bypass 放行")
    void unseededReturnsBypass() {
        SeckillResult r = service.tryPreDeduct(SKU, 1);
        assertThat(r.allowed()).isTrue();
        assertThat(r.bypassed()).isTrue();
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
