package com.payment.order.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.order.api.dto.CreateOrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class OrderEntryIdempotencyServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private BusinessMetrics metrics;
    private OrderEntryIdempotencyService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        metrics = mock(BusinessMetrics.class);
        service = new OrderEntryIdempotencyService(redis, new ObjectMapper(), metrics);
    }

    private CreateOrderResponse sampleResponse() {
        return new CreateOrderResponse(10L, 20L, "PAID", 9900L, "CNY", 30L, "SUCCEEDED");
    }

    @Test
    @DisplayName("首次请求（SETNX 成功）：返回 PROCEED；完成后写入 DONE 结果")
    void firstAttemptProceedsAndStoresDone() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);

        IdempotencyDecision d = service.check("k-1");
        assertThat(d.isProceed()).isTrue();

        service.complete("k-1", sampleResponse());
        verify(valueOps, times(1)).set(eq("idemp:order:k-1"),
                org.mockito.ArgumentMatchers.startsWith("DONE:"), any());
    }

    @Test
    @DisplayName("无幂等键：直接 PROCEED，不触碰 Redis")
    void noKeyProceedsWithoutRedis() {
        IdempotencyDecision d = service.check(null);
        assertThat(d.isProceed()).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("并发重复（IN_PROGRESS）：返回 CONFLICT（调用方应 409 + Retry-After）")
    void concurrentDuplicateConflicts() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);
        when(valueOps.get(anyString())).thenReturn("IN_PROGRESS");

        IdempotencyDecision d = service.check("k-2");
        assertThat(d.isConflict()).isTrue();
    }

    @Test
    @DisplayName("已完成重复（DONE）：返回 REPLAY 并携带首次响应 JSON")
    void completedDuplicateReplays() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);
        when(valueOps.get(anyString()))
                .thenReturn("DONE:" + "{\"orderId\":10,\"transactionId\":20,\"status\":\"PAID\"}");

        IdempotencyDecision d = service.check("k-3");
        assertThat(d.isReplay()).isTrue();
        assertThat(d.storedJson().orElse("")).contains("\"orderId\":10");
    }

    @Test
    @DisplayName("Redis 不可用：fail-open 返回 UNAVAILABLE，并记降级指标，不抛异常")
    void redisUnavailableFailOpen() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("redis down"));

        IdempotencyDecision d = service.check("k-4");
        assertThat(d.isUnavailable()).isTrue();
        verify(metrics, times(1)).counter(eq("order_idempotency_degraded_total"), eq(1.0), eq("reason"), eq("redis_unavailable"));
    }
}
