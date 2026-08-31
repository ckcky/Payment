package com.payment.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import com.payment.catalog.infra.InMemoryProductRepository;
import com.payment.catalog.infra.InMemorySkuRepository;
import com.payment.catalog.infra.cache.SkuCache;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code GET /skus} 列表能力的应用层测试：确保 listSkus 返回仓储中所有 SKU（演示控制台依赖它
 * 动态解析真实 SKU id，避免硬编码 101/102/103）。
 */
class CatalogListSkuTest {

    @Test
    void listSkusReturnsAllSavedSkus() {
        InMemorySkuRepository skuRepo = new InMemorySkuRepository();
        InMemoryProductRepository productRepo = new InMemoryProductRepository();
        // 关闭缓存，直连内存仓储，保证测试不依赖 Redis
        CatalogCacheProperties cacheProps = new CatalogCacheProperties();
        cacheProps.setEnabled(false);
        SkuCache skuCache = new SkuCache(mock(StringRedisTemplate.class), new ObjectMapper(), skuRepo, cacheProps);
        CatalogApplicationService service = new CatalogApplicationService(productRepo, skuRepo, skuCache);

        service.createSku("DEMO-SKU-101", 1L, "月度会员卡", 9900L, "CNY", "AUTO_GRANT");
        service.createSku("DEMO-SKU-102", 1L, "年度会员卡", 129000L, "CNY", "AUTO_GRANT");
        service.createSku("DEMO-SKU-103", 1L, "秒杀体验卡", 100L, "CNY", "AUTO_GRANT");

        List<Sku> all = service.listSkus();
        assertEquals(3, all.size(), "listSkus 应返回全部 3 个种子 SKU");

        // 验证 id 为自增（不保证是 101/102/103），演示控制台必须动态解析
        long distinctIds = all.stream().mapToLong(Sku::getId).distinct().count();
        assertEquals(3, distinctIds, "三个 SKU 应有互不相同的自增 id");
    }
}
