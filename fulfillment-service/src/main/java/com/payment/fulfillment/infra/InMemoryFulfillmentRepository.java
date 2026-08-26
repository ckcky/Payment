package com.payment.fulfillment.infra;

import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版履约仓储（MVP：无持久化）。线程安全，用 {@code sourcePaymentId} 维护幂等索引。
 */
@Repository
public class InMemoryFulfillmentRepository implements FulfillmentRepository {

    private final ConcurrentHashMap<Long, Fulfillment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bySourcePaymentId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Optional<Fulfillment> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Fulfillment> findBySourcePaymentId(String sourcePaymentId) {
        Long id = bySourcePaymentId.get(sourcePaymentId);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Fulfillment save(Fulfillment fulfillment) {
        if (fulfillment.getId() == null) {
            fulfillment.setId(idGenerator.incrementAndGet());
        }
        byId.put(fulfillment.getId(), fulfillment);
        bySourcePaymentId.put(fulfillment.getSourcePaymentId(), fulfillment.getId());
        return fulfillment;
    }
}
