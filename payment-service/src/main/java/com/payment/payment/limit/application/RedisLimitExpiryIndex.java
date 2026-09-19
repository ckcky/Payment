package com.payment.payment.limit.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.limit.infra.LimitProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 实现在途占用过期索引（spec 027 / ADR-0071 D13，生产实现）。
 *
 * <p><b>key 规范</b>：{@code {key-prefix}{paymentNo}}（默认 {@code limit:pending:PM...}）。
 * value 存金额（分）仅作可读性——判定路径<b>不读</b>它（INV-9.1）。</p>
 *
 * <p><b>全部操作 fail-open</b>：Redis 出任何问题都只记指标、不抛异常。
 * 建单路径不得因一个「过期索引」的基础设施抖动而失败——这是本决策能被接受的前提
 * （INV-9.2：不可用 → 保守占用、不拦截）。</p>
 *
 * <p><b>与 {@code NoopLimitExpiryIndex} 的区别</b>：本类是「配了 Redis 但暂时连不上」，
 * 每次调用都重试（连接自愈）；Noop 是「压根没配」。</p>
 */
public class RedisLimitExpiryIndex implements LimitExpiryIndex {

    private static final Logger log = LoggerFactory.getLogger(RedisLimitExpiryIndex.class);

    static final String METRIC_UNAVAILABLE = "payment.limit.redis_unavailable";
    static final String METRIC_ERROR = "payment.limit.redis_error";

    private final StringRedisTemplate redis;
    private final LimitProperties properties;
    private final BusinessMetrics metrics;

    public RedisLimitExpiryIndex(StringRedisTemplate redis, LimitProperties properties,
                                 BusinessMetrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public void mark(String paymentNo, long amountMinor, Duration ttl) {
        if (paymentNo == null || paymentNo.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(key(paymentNo), String.valueOf(amountMinor), ttl);
        } catch (RuntimeException e) {
            // 不抛出：在途标记写不进去，最坏后果是这笔占用不会被惰性回收
            // （即「保守占用」），方向与 INV-9.2 一致——约束变紧不变松。
            metrics.counter(METRIC_ERROR, 1.0, "op", "mark");
            log.warn("写入额度在途标记失败（保守占用，不影响建单）paymentNo={} reason={}",
                    paymentNo, e.getMessage());
        }
    }

    @Override
    public void clear(String paymentNo) {
        if (paymentNo == null || paymentNo.isBlank()) {
            return;
        }
        try {
            redis.delete(key(paymentNo));
        } catch (RuntimeException e) {
            // 不抛出：删不掉只意味着这个 key 会自然过期，无正确性影响
            metrics.counter(METRIC_ERROR, 1.0, "op", "clear");
            log.warn("清除额度在途标记失败（key 将自然过期，无正确性影响）paymentNo={} reason={}",
                    paymentNo, e.getMessage());
        }
    }

    /**
     * MGET 批量判存。
     *
     * @return 仍存活的支付单号；<b>Redis 不可用返回 {@code null}</b>（调用方据此跳过回收）
     */
    @Override
    public Set<String> alive(Collection<String> paymentNos) {
        if (paymentNos == null || paymentNos.isEmpty()) {
            return new HashSet<>();
        }
        List<String> keys = new ArrayList<>(paymentNos.size());
        for (String paymentNo : paymentNos) {
            keys.add(key(paymentNo));
        }
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null) {
                return null;
            }
            Set<String> result = new HashSet<>();
            int i = 0;
            for (String paymentNo : paymentNos) {
                String value = i < values.size() ? values.get(i) : null;
                if (value != null) {
                    result.add(paymentNo);
                }
                i++;
            }
            return result;
        } catch (RuntimeException e) {
            // 无法判定 ≠ 全部过期（INV-9.2）：返回 null 让调用方保守占用。
            // 若这里错误地返回空集，所有在途占用会被当成已过期批量释放 —— 限额被静默击穿。
            metrics.counter(METRIC_UNAVAILABLE, 1.0, "op", "alive");
            log.warn("额度过期索引不可用，跳过在途回收（保守占用）count={} reason={}",
                    paymentNos.size(), e.getMessage());
            return null;
        }
    }

    private String key(String paymentNo) {
        return properties.getRedis().getKeyPrefix() + paymentNo;
    }

    /** 供测试断言 TTL 用。 */
    Long ttlSeconds(String paymentNo) {
        return redis.getExpire(key(paymentNo), TimeUnit.SECONDS);
    }
}
