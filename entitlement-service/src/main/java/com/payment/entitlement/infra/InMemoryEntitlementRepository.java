package com.payment.entitlement.infra;

import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内仓储实现，用于 MVP 与基础测试；生产需替换为持久化实现（见各服务 infra 层约定）。
 */
@Repository
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
    public Entitlement save(Entitlement entitlement) {
        if (entitlement.getId() == null) {
            entitlement.setId(idGenerator.getAndIncrement());
        }
        byId.put(entitlement.getId(), entitlement);
        bySourceFulfillmentId.put(entitlement.getSourceFulfillmentId(), entitlement);
        return entitlement;
    }
}
