package com.payment.order.application.ratelimit;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 固定窗口限流器（014）：按 bucket 计数，窗口内超过容量则拒绝。
 * 线程安全（synchronized），单机演示足够；分布式部署应改用 Redis 令牌桶/Lua。
 *
 * <p>语义（owner 决策「快速失败，拒绝不允许重试」）：超限直接拒绝（429），
 * 不返回 Retry-After，调用方应 fail-fast 而非重试。</p>
 */
@Component
public class RateLimiter {

    private static final class Window {
        final long start;
        long count;

        Window(long start, long count) {
            this.start = start;
            this.count = count;
        }
    }

    private final Map<String, Window> windows = new HashMap<>();

    /** 尝试获取一个配额；窗口内未超限返回 true，否则 false。 */
    public synchronized boolean tryAcquire(String bucket, int capacity, long windowMillis) {
        long now = System.currentTimeMillis();
        Window w = windows.get(bucket);
        if (w == null || now - w.start >= windowMillis) {
            windows.put(bucket, new Window(now, 1));
            return true;
        }
        if (w.count < capacity) {
            w.count++;
            return true;
        }
        return false;
    }
}
