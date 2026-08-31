package com.payment.catalog.infra;

import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 SKU 仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisSkuRepository} 承接。
 */
public class InMemorySkuRepository implements SkuRepository {

    private final ConcurrentMap<Long, Sku> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Optional<Sku> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Sku> findByCode(String skuCode) {
        return store.values().stream()
                .filter(s -> s.getSkuCode().equals(skuCode))
                .findFirst();
    }

    @Override
    public List<Sku> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public Sku save(Sku sku) {
        if (sku.getId() == null) {
            sku.setId(idGenerator.incrementAndGet());
        }
        store.put(sku.getId(), sku);
        return sku;
    }
}
