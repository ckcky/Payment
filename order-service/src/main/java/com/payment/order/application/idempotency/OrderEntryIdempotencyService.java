package com.payment.order.application.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.order.api.dto.CreateOrderResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 下单入口幂等服务（ADR-0039/0040 裁决：客户端生成 {@code Idempotency-Key}，服务端仅用 Redis 防重，
 * 不落幂等表；后期 Phase 3 的资金入口幂等仍走 DB 唯一约束，二者互不冲突）。
 *
 * <p>语义（不接管 + 轮询，ADR-0040 推荐）：</p>
 * <ol>
 *   <li>首次（key 不存在）：SETNX 占位 {@code IN_PROGRESS(30s)}，订单业务继续；完成后写入
 *       {@code DONE:<responseJson>(24h)}。</li>
 *   <li>并发重复（key 处于 {@code IN_PROGRESS}）：返回 {@link IdempotencyDecision.Type#CONFLICT}，
 *       调用方 409 + Retry-After 轮询，不重复创建。</li>
 *   <li>已完成重复（key 处于 {@code DONE}）：返回 {@link IdempotencyDecision.Type#REPLAY}，
 *       携带首次响应（调用方 200 拿到相同结果）。</li>
 *   <li>Redis 不可用：fail-open，记 {@code order_idempotency_degraded_total}，当首次请求处理（不阻断下单）。</li>
 * </ol>
 *
 * <p>放弃策略：{@code IN_PROGRESS} 带 30s TTL，业务失败未调用 {@code complete} 时自动过期，
 * 允许客户端在 TTL 后重试（崩溃窗口内的极小重复作为可接受代价，见 next-stage-design §5.1）。</p>
 */
@Service
public class OrderEntryIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(OrderEntryIdempotencyService.class);

    private static final String PREFIX = "idemp:order:";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String DONE_PREFIX = "DONE:";
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);
    private static final Duration DONE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final BusinessMetrics metrics;

    public OrderEntryIdempotencyService(StringRedisTemplate redis, ObjectMapper mapper, BusinessMetrics metrics) {
        this.redis = redis;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /**
     * 检查幂等键状态，决定本次下单如何处理。
     */
    public IdempotencyDecision check(String key) {
        if (key == null || key.isBlank()) {
            return IdempotencyDecision.proceed(); // 无 key：不防重，直接创建
        }
        String redisKey = PREFIX + key;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, IN_PROGRESS_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return IdempotencyDecision.proceed();
            }
            // key 已存在 → 区分 DONE（已完成）与 IN_PROGRESS（并发处理中）
            String existing = redis.opsForValue().get(redisKey);
            if (existing != null && existing.startsWith(DONE_PREFIX)) {
                return IdempotencyDecision.replay(existing.substring(DONE_PREFIX.length()));
            }
            return IdempotencyDecision.conflict();
        } catch (RuntimeException ex) {
            // fail-open：Redis 不可用时不阻断下单，仅记指标
            log.warn("order idempotency store unavailable, fail-open (key={}): {}", mask(key), ex.getMessage());
            metrics.counter("order_idempotency_degraded_total", 1.0, "reason", "redis_unavailable");
            return IdempotencyDecision.unavailable();
        }
    }

    /**
     * 订单创建成功后写入幂等结果（供后续同 key 重放）。Redis 不可用时静默失败（fail-open）。
     */
    public void complete(String key, CreateOrderResponse response) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + key, DONE_PREFIX + serialize(response), DONE_TTL);
        } catch (RuntimeException ex) {
            log.warn("order idempotency complete failed, fail-open (key={}): {}", mask(key), ex.getMessage());
            metrics.counter("order_idempotency_degraded_total", 1.0, "reason", "redis_unavailable");
        }
    }

    private String serialize(CreateOrderResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize idempotency response failed", e);
        }
    }

    private static String mask(String key) {
        return key == null ? "null" : (key.length() <= 4 ? key : key.substring(0, 4) + "***");
    }
}
