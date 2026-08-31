package com.payment.order.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 固定窗口限流器单元测试（014）：验证容量约束、桶隔离与窗口重置。
 */
class RateLimiterTest {

    @Test
    void withinWindowEnforcesCapacity() {
        RateLimiter limiter = new RateLimiter();
        String bucket = "b";
        assertThat(limiter.tryAcquire(bucket, 2, 1000)).isTrue();
        assertThat(limiter.tryAcquire(bucket, 2, 1000)).isTrue();
        assertThat(limiter.tryAcquire(bucket, 2, 1000)).isFalse();
    }

    @Test
    void differentBucketsAreIndependent() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.tryAcquire("a", 1, 1000)).isTrue();
        assertThat(limiter.tryAcquire("a", 1, 1000)).isFalse();
        assertThat(limiter.tryAcquire("b", 1, 1000)).isTrue();
    }

    @Test
    void windowResetsAfterExpiry() throws Exception {
        RateLimiter limiter = new RateLimiter();
        String bucket = "c";
        assertThat(limiter.tryAcquire(bucket, 1, 20)).isTrue();
        assertThat(limiter.tryAcquire(bucket, 1, 20)).isFalse();
        Thread.sleep(40);
        assertThat(limiter.tryAcquire(bucket, 1, 20)).isTrue();
    }
}
