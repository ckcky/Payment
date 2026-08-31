package com.payment.catalog.application;

import com.payment.catalog.domain.Product;
import com.payment.catalog.domain.ProductRepository;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import com.payment.catalog.infra.cache.SkuCache;
import java.util.List;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.springframework.stereotype.Service;

/**
 * Catalog 应用服务：编排商品/SKU 的创建与状态流转。
 */
@Service
public class CatalogApplicationService {

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final SkuCache skuCache;

    public CatalogApplicationService(ProductRepository productRepository, SkuRepository skuRepository, SkuCache skuCache) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.skuCache = skuCache;
    }

    public Product createProduct(String productCode, String name, String type) {
        productRepository.findByCode(productCode).ifPresent(existing -> {
            throw BizException.of(ErrorCodes.CONFLICT, "product code already exists: " + productCode);
        });
        return productRepository.save(new Product(productCode, name, type));
    }

    public Product listProduct(Long id) {
        Product product = getProduct(id);
        product.list();
        return productRepository.save(product);
    }

    public Product getProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "product not found: " + id));
    }

    public Sku createSku(String skuCode, Long productId, String name, long priceMinor,
                         String currencyCode, String deliveryDefinition) {
        skuRepository.findByCode(skuCode).ifPresent(existing -> {
            throw BizException.of(ErrorCodes.CONFLICT, "sku code already exists: " + skuCode);
        });
        Sku saved = skuRepository.save(new Sku(skuCode, productId, name, priceMinor, currencyCode, deliveryDefinition));
        skuCache.evict(saved.getId(), saved.getSkuCode());
        return saved;
    }

    public Sku activateSku(Long id) {
        Sku sku = getSku(id);
        sku.activate();
        Sku saved = skuRepository.save(sku);
        skuCache.evict(saved.getId(), saved.getSkuCode());
        return saved;
    }

    public Sku suspendSku(Long id) {
        Sku sku = getSku(id);
        sku.suspend();
        Sku saved = skuRepository.save(sku);
        skuCache.evict(saved.getId(), saved.getSkuCode());
        return saved;
    }

    public Sku getSku(Long id) {
        Sku sku = skuCache.getById(id);
        if (sku == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "sku not found: " + id);
        }
        return sku;
    }

    /** 列出全部 SKU（只读，供演示控制台动态解析真实 SKU id，避免硬编码）。 */
    public List<Sku> listSkus() {
        return skuRepository.findAll();
    }
}
