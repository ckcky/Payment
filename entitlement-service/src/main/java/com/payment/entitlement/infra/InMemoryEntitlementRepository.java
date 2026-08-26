package com.payment.entitlement.infra;

import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存权益仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisEntitlementRepository} 承接。
 */
public class InMemoryEntitlementRepository implements EntitlementRepository {

    private final Map<Long, Entitlement> byId = new ConcurrentHashMap<>();
    private final Map<String, Entitlement> bySourceFulfillmentId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1L);

    @Override
    public Optional<Entitlement> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Entitlement> findBySourceFulfillmentId(String sourceFulfillmentId) {
        return Optional.ofNullable(bySourceFulfillmentId.get(sourceFulfillmentId));
    }

    @Override
    public List<Entitlement> findByOrderId(String orderId) {
        return byId.values().stream()
                .filter(e -> orderId.equals(e.getOrderId()))
                .toList();
    }

    @Override
    public Entitlement save(Entitlement entitlement) {
        if (entitlement.getId() == null) {
            entitlement.setId(idGenerator.getAndIncrement());
        }
        byId.put(entitlement.getId(), entitlement);
        bySourceFulfillmentId.put(entitlement.getSourceFulfillmentId(), entitlement);
        return entitlement;
    }
}
