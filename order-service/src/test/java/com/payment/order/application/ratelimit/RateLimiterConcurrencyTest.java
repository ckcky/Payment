package com.payment.order.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 固定窗口限流器并发量化测试（R3 🟡#3）：在单窗口内以高并发抢占配额，
 * 断言「最多 capacity 个请求成功，其余被拒」——验证不超发、不漏拦。
 */
class RateLimiterConcurrencyTest {

    @Test
    @DisplayName("单窗口 100 并发抢占容量 10：恰好 10 个成功，90 个被拒")
    void singleWindowCapsAtCapacityUnderConcurrency() throws Exception {
        RateLimiter limiter = new RateLimiter();
        final int capacity = 10;
        final int threads = 100;
        final String bucket = "hot-bucket";

        AtomicInteger acquired = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire(bucket, capacity, 5000)) {
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // synchronized 保证窗口内至多 capacity 个成功；并发不应导致超发或漏拦
        assertThat(acquired.get()).isEqualTo(capacity);
    }
}
