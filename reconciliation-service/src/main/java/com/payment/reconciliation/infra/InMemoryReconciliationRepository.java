package com.payment.reconciliation.infra;

import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存对账批次仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 MyBatis 实现承接。
 */
public class InMemoryReconciliationRepository implements ReconciliationRepository {

    private final Map<Long, ReconciliationBatch> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<ReconciliationBatch> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ReconciliationBatch> findByPeriod(String period) {
        return byId.values().stream()
                .filter(b -> period.equals(b.getPeriod()))
                .findFirst();
    }

    @Override
    public List<ReconciliationBatch> findByPeriodBetween(String from, String to) {
        return byId.values().stream()
                .filter(b -> b.getPeriod().compareTo(from) >= 0 && b.getPeriod().compareTo(to) <= 0)
                .toList();
    }

    @Override
    public ReconciliationBatch save(ReconciliationBatch batch) {
        if (batch.getId() == null) {
            batch.setId(idGen.incrementAndGet());
        }
        byId.put(batch.getId(), batch);
        return batch;
    }
}
