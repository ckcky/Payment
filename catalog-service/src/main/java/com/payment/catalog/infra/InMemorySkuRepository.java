package com.payment.catalog.infra;

import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版 Sku 仓储（MVP，无持久化）。ConcurrentHashMap + AtomicLong 保证并发安全。
 */
@Repository
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
    public Sku save(Sku sku) {
        if (sku.getId() == null) {
            sku.setId(idGenerator.incrementAndGet());
        }
        store.put(sku.getId(), sku);
        return sku;
    }
}
