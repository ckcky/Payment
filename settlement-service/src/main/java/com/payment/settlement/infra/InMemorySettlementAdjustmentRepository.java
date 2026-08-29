package com.payment.settlement.infra;

import com.payment.settlement.domain.SettlementAdjustment;
import com.payment.settlement.domain.SettlementAdjustmentRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存结算调整项仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 MyBatis 实现承接。
 */
public class InMemorySettlementAdjustmentRepository implements SettlementAdjustmentRepository {

    private final Map<Long, SettlementAdjustment> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<SettlementAdjustment> findByIdempotencyKey(String idempotencyKey) {
        return byId.values().stream()
                .filter(a -> idempotencyKey.equals(a.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public List<SettlementAdjustment> findActiveByMerchantAndPeriod(String merchantId, String period) {
        return byId.values().stream()
                .filter(a -> merchantId.equals(a.getMerchantId())
                        && period.equals(a.getPeriod())
                        && a.getStatus() == com.payment.settlement.domain.AdjustmentStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    @Override
    public SettlementAdjustment save(SettlementAdjustment adjustment) {
        if (adjustment.getId() == null) {
            SettlementAdjustment saved = SettlementAdjustment.rehydrate(idGen.incrementAndGet(),
                    adjustment.getVersion(), adjustment.getIdempotencyKey(), adjustment.getMerchantId(),
                    adjustment.getPeriod(), adjustment.getAmountMinor(), adjustment.getDirection(),
                    adjustment.getCurrencyCode(), adjustment.getReason(), adjustment.getOperator(),
                    adjustment.getStatus(), adjustment.getCreatedAt());
            byId.put(saved.getId(), saved);
            return saved;
        }
        byId.put(adjustment.getId(), adjustment);
        return adjustment;
    }
}
