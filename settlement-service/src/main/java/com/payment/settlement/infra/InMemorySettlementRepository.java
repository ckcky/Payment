package com.payment.settlement.infra;

import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存结算仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 MyBatis 实现承接。
 */
public class InMemorySettlementRepository implements SettlementRepository {

    private final Map<Long, SettlementBatch> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<SettlementBatch> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<SettlementBatch> findByMerchantAndPeriod(String merchantId, String period) {
        return byId.values().stream()
                .filter(b -> merchantId.equals(b.getMerchantId()) && period.equals(b.getPeriod()))
                .findFirst();
    }

    @Override
    public Optional<SettlementBatch> findByIdempotencyKey(String idempotencyKey) {
        return byId.values().stream()
                .filter(b -> idempotencyKey.equals(b.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public List<SettlementBatch> listBatches(String merchantId, String period) {
        return byId.values().stream()
                .filter(b -> merchantId == null || merchantId.isBlank() || merchantId.equals(b.getMerchantId()))
                .filter(b -> period == null || period.isBlank() || period.equals(b.getPeriod()))
                .toList();
    }

    @Override
    public SettlementBatch save(SettlementBatch batch) {
        if (batch.getId() == null) {
            batch.setId(idGen.incrementAndGet());
        }
        byId.put(batch.getId(), batch);
        return batch;
    }
}
