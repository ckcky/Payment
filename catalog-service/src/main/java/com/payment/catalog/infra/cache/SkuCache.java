package com.payment.catalog.infra.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.catalog.application.CatalogCacheProperties;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SKU 读缓存（014，cache-aside）。
 *
 * <p>读路径：先查 Redis，命中直接返回；未命中从仓储加载并回写（带 TTL）。
 * 写路径（创建/激活/暂停）通过 {@link #evict} 失效对应键。</p>
 *
 * <p>fail-open：Redis 不可用或序列化异常时记录日志并回退到仓储，绝不阻断读。</p>
 */
@Component
public class SkuCache {

    private static final Logger log = LoggerFactory.getLogger(SkuCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SkuRepository repository;
    private final CatalogCacheProperties props;

    public SkuCache(StringRedisTemplate redis, ObjectMapper mapper, SkuRepository repository,
                   CatalogCacheProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.repository = repository;
        this.props = props;
    }

    public Sku getById(Long id) {
        return readThrough(props.getKeyPrefix() + "id:" + id,
                () -> repository.findById(id).orElse(null));
    }

    public Sku getByCode(String code) {
        return readThrough(props.getKeyPrefix() + "code:" + code,
                () -> repository.findByCode(code).orElse(null));
    }

    /** 失效缓存条目（写入/状态流转后调用）。 */
    public void evict(Long id, String code) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            if (id != null) {
                redis.delete(props.getKeyPrefix() + "id:" + id);
            }
            if (code != null) {
                redis.delete(props.getKeyPrefix() + "code:" + code);
            }
        } catch (RuntimeException ex) {
            log.warn("sku cache evict failed (fail-open): {}", ex.getMessage());
        }
    }

    private Sku readThrough(String key, Supplier<Sku> loader) {
        if (props.isEnabled()) {
            try {
                String json = redis.opsForValue().get(key);
                if (json != null) {
                    return mapper.readValue(json, SkuCacheView.class).toSku();
                }
            } catch (RuntimeException | IOException ex) {
                log.warn("sku cache read failed (fallback to repository): {}", ex.getMessage());
            }
        }
        Sku sku = loader.get();
        if (sku != null && props.isEnabled()) {
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(SkuCacheView.from(sku)),
                        Duration.ofSeconds(props.getTtlSeconds()));
            } catch (RuntimeException | JsonProcessingException ex) {
                log.warn("sku cache write failed (fail-open): {}", ex.getMessage());
            }
        }
        return sku;
    }
}
