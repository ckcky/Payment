package com.payment.catalog.infra;

import com.payment.catalog.domain.Product;
import com.payment.catalog.domain.ProductRepository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存商品仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisProductRepository} 承接。
 */
public class InMemoryProductRepository implements ProductRepository {

    private final ConcurrentMap<Long, Product> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Optional<Product> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Product> findByCode(String productCode) {
        return store.values().stream()
                .filter(p -> p.getProductCode().equals(productCode))
                .findFirst();
    }

    @Override
    public Product save(Product product) {
        if (product.getId() == null) {
            product.setId(idGenerator.incrementAndGet());
        }
        store.put(product.getId(), product);
        return product;
    }
}
