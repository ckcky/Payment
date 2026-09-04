package com.payment.fulfillment.infra;

import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版履约仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisFulfillmentRepository} 承接。
 * 线程安全，用 {@code sourcePaymentNo} 维护幂等索引。
 */
public class InMemoryFulfillmentRepository implements FulfillmentRepository {

    private final ConcurrentHashMap<Long, Fulfillment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bySourcePaymentId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Optional<Fulfillment> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Fulfillment> findBySourcePaymentId(String sourcePaymentNo) {
        Long id = bySourcePaymentId.get(sourcePaymentNo);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Fulfillment> findByOrderNo(String orderNo) {
        return byId.values().stream()
                .filter(f -> orderNo.equals(f.getOrderNo()))
                .findFirst();
    }

    @Override
    public Fulfillment save(Fulfillment fulfillment) {
        if (fulfillment.getId() == null) {
            fulfillment.setId(idGenerator.incrementAndGet());
        }
        byId.put(fulfillment.getId(), fulfillment);
        bySourcePaymentId.put(fulfillment.getSourcePaymentNo(), fulfillment.getId());
        return fulfillment;
    }
}
