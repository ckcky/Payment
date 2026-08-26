package com.payment.merchant.infra;

import com.payment.merchant.domain.Merchant;
import com.payment.merchant.domain.MerchantRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of {@link MerchantRepository} backed by a
 * {@link ConcurrentHashMap} and an {@link AtomicLong} id generator.
 */
@Repository
public class InMemoryMerchantRepository implements MerchantRepository {

    private final Map<Long, Merchant> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Optional<Merchant> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Merchant> findByCode(String merchantCode) {
        return store.values().stream()
                .filter(merchant -> merchant.getMerchantCode().equals(merchantCode))
                .findFirst();
    }

    @Override
    public Merchant save(Merchant merchant) {
        if (merchant.getId() == null) {
            merchant.setId(idGenerator.incrementAndGet());
        }
        store.put(merchant.getId(), merchant);
        return merchant;
    }
}
