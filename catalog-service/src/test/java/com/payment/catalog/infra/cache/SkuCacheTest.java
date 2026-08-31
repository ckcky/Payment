package com.payment.catalog.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.catalog.application.CatalogCacheProperties;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * SKU 缓存单元测试（014）：验证 cache-aside 的命中/回写/失效语义，以及关闭开关后的 fail-open。
 */
@ExtendWith(MockitoExtension.class)
class SkuCacheTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @Mock
    private SkuRepository repo;

    private final Map<String, String> store = new ConcurrentHashMap<>();

    private SkuCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        lenient().doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(redis).delete(anyString());

        CatalogCacheProperties props = new CatalogCacheProperties();
        props.setEnabled(true);
        cache = new SkuCache(redis, new ObjectMapper(), repo, props);
    }

    private Sku sample(Long id) {
        Sku sku = new Sku("C1", 1L, "n", 100, "CNY", "D");
        sku.setId(id);
        sku.setVersion(0);
        return sku;
    }

    @Test
    void cacheMissLoadsFromRepoAndWritesBack() {
        when(repo.findById(7L)).thenReturn(Optional.of(sample(7L)));

        Sku got = cache.getById(7L);

        assertThat(got.getId()).isEqualTo(7L);
        verify(repo, times(1)).findById(7L);
        assertThat(store).isNotEmpty(); // 回写成功
    }

    @Test
    void secondReadHitsCacheWithoutReloadingRepo() {
        when(repo.findById(7L)).thenReturn(Optional.of(sample(7L)));

        cache.getById(7L); // miss -> load + write
        Sku second = cache.getById(7L); // hit

        assertThat(second.getId()).isEqualTo(7L);
        verify(repo, times(1)).findById(7L); // 仅一次
    }

    @Test
    void evictForcesReloadFromRepo() {
        when(repo.findById(7L)).thenReturn(Optional.of(sample(7L)));

        cache.getById(7L);
        cache.evict(7L, "C1");
        cache.getById(7L); // 失效后重新加载

        verify(repo, times(2)).findById(7L);
    }

    @Test
    void disabledBypassesCacheAndAlwaysHitsRepo() {
        CatalogCacheProperties disabled = new CatalogCacheProperties();
        disabled.setEnabled(false);
        SkuCache off = new SkuCache(redis, new ObjectMapper(), repo, disabled);
        when(repo.findById(7L)).thenReturn(Optional.of(sample(7L)));

        off.getById(7L);
        off.getById(7L);

        verify(repo, times(2)).findById(7L);
        verify(ops, never()).get(anyString());
    }
}
