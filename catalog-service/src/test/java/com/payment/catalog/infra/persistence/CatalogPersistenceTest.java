package com.payment.catalog.infra.persistence;

import com.payment.catalog.domain.Product;
import com.payment.catalog.domain.ProductRepository;
import com.payment.catalog.domain.ProductStatus;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import com.payment.catalog.domain.SkuStatus;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品/SKU 持久化集成测试（H2，MySQL 兼容模式）：验证 PO↔领域映射、审计字段、乐观锁。
 */
@SpringBootTest
class CatalogPersistenceTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Test
    void productRoundTrip() {
        Product product = new Product("P-1001", "Annual Membership", "MEMBERSHIP");
        product.list();
        productRepository.save(product);

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(product.getId());
        assertThat(reloaded.getProductCode()).isEqualTo("P-1001");
        assertThat(reloaded.getName()).isEqualTo("Annual Membership");
        assertThat(reloaded.getType()).isEqualTo("MEMBERSHIP");
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.LISTED);
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(productRepository.findByCode("P-1001")).isPresent();
    }

    @Test
    void skuRoundTrip() {
        Sku sku = new Sku("SKU-1001", 1L, "Annual Membership", 1999L, "CNY", "digital");
        sku.activate();
        skuRepository.save(sku);

        Sku reloaded = skuRepository.findById(sku.getId()).orElseThrow();
        assertThat(reloaded.getSkuCode()).isEqualTo("SKU-1001");
        assertThat(reloaded.getProductId()).isEqualTo(1L);
        assertThat(reloaded.getName()).isEqualTo("Annual Membership");
        assertThat(reloaded.getPriceMinor()).isEqualTo(1999L);
        assertThat(reloaded.getCurrencyCode()).isEqualTo("CNY");
        assertThat(reloaded.getStatus()).isEqualTo(SkuStatus.SELLABLE);
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(skuRepository.findByCode("SKU-1001")).isPresent();
    }

    @Test
    void optimisticLockRejectsStaleUpdate() {
        Product product = new Product("P-1001", "Annual Membership", "MEMBERSHIP");
        product.list();
        productRepository.save(product);

        Product first = productRepository.findById(product.getId()).orElseThrow();
        Product second = productRepository.findById(product.getId()).orElseThrow();

        first.unlist();
        productRepository.save(first);

        second.unlist();
        assertThatThrownBy(() -> productRepository.save(second))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }
}
