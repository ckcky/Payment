package com.payment.catalog.application.seckill;

import com.payment.common.core.observability.BusinessMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 秒杀配额预扣服务（014）：基于 Redis Lua 脚本做原子预扣，避免下单洪峰直接冲击 DB。
 *
 * <p>语义：
 * <ul>
 *   <li>下单前对秒杀 SKU 调用 {@link #tryPreDeduct}：Lua 原子判断配额，充足则扣减返回剩余，不足返回 deny。</li>
 *   <li>未播种配额（非秒杀 SKU）返回 bypass，由 DB 三段式库存正常处理。</li>
 *   <li>订单失败/超时通过 {@link #rollback} 回补配额。</li>
 *   <li>Redis 不可用时 fail-closed（拒绝），宁可拦截也不绕过库存保护（与「Redis 非数据源」原则一致）。</li>
 * </ul>
 */
@Service
public class SeckillStockService {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockService.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> deductScript;
    private final SeckillProperties props;
    private final BusinessMetrics metrics;

    public SeckillStockService(StringRedisTemplate redis, RedisScript<Long> deductScript,
                               SeckillProperties props, BusinessMetrics metrics) {
        this.redis = redis;
        this.deductScript = deductScript;
        this.props = props;
        this.metrics = metrics;
    }

    /** 为秒杀 SKU 播种 Redis 配额（演示/测试用）。 */
    public void seed(Long skuId, long total) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            redis.opsForValue().set(seckillKey(skuId), String.valueOf(total));
        } catch (RuntimeException ex) {
            log.warn("seckill seed failed (fail-open): {}", ex.getMessage());
        }
    }

    /** Lua 原子预扣：返回 allow/bypass/deny 三态结果。 */
    public SeckillResult tryPreDeduct(Long skuId, long quantity) {
        if (!props.isEnabled()) {
            return SeckillResult.bypass();
        }
        Long remaining;
        try {
            remaining = redis.execute(deductScript, List.of(seckillKey(skuId)), String.valueOf(quantity));
        } catch (RuntimeException ex) {
            log.warn("seckill deduct failed (redis unavailable, deny to protect stock): {}", ex.getMessage());
            metrics.counter("catalog_seckill_degraded_total", 1.0, "reason", "redis_unavailable");
            return SeckillResult.deny();
        }
        if (remaining == null || remaining == -2) {
            return SeckillResult.bypass();
        }
        if (remaining < 0) {
            metrics.counter("catalog_seckill_denied_total", 1.0, "reason", "quota_exhausted");
            return SeckillResult.deny();
        }
        metrics.counter("catalog_seckill_admitted_total", 1.0);
        return SeckillResult.allowed(remaining);
    }

    /**
     * 秒杀订单失败/超时回补配额。
     *
     * <p>仅当配额键已存在时才回补：{@code INCR} 对不存在的键会凭空造出配额键，
     * 使普通品（从未播种）被后续下单误判为"秒杀售罄"（409）。
     * 2026-09-05 实测踩坑：订单超时取消对所有商品无条件回补，普通品流量被 409 拒单。</p>
     */
    public void rollback(Long skuId, long quantity) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            Boolean exists = redis.hasKey(seckillKey(skuId));
            if (!Boolean.TRUE.equals(exists)) {
                return;
            }
            redis.opsForValue().increment(seckillKey(skuId), quantity);
        } catch (RuntimeException ex) {
            log.warn("seckill rollback failed: {}", ex.getMessage());
        }
    }

    private String seckillKey(Long skuId) {
        return "seckill:sku:" + skuId;
    }
}
