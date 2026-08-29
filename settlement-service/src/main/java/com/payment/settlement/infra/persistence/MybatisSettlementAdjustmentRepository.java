package com.payment.settlement.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.settlement.domain.AdjustmentDirection;
import com.payment.settlement.domain.AdjustmentStatus;
import com.payment.settlement.domain.SettlementAdjustment;
import com.payment.settlement.domain.SettlementAdjustmentRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 结算调整项仓储 MyBatis 实现（ADR-0022）：独立建表，方向/状态以枚举名持久化。
 */
@Repository
public class MybatisSettlementAdjustmentRepository implements SettlementAdjustmentRepository {

    private final SettlementAdjustmentMapper mapper;

    public MybatisSettlementAdjustmentRepository(SettlementAdjustmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SettlementAdjustment> findByIdempotencyKey(String idempotencyKey) {
        SettlementAdjustmentEntity entity = mapper.selectOne(Wrappers.<SettlementAdjustmentEntity>lambdaQuery()
                .eq(SettlementAdjustmentEntity::getIdempotencyKey, idempotencyKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<SettlementAdjustment> findActiveByMerchantAndPeriod(String merchantId, String period) {
        return mapper.selectList(Wrappers.<SettlementAdjustmentEntity>lambdaQuery()
                        .eq(SettlementAdjustmentEntity::getMerchantId, merchantId)
                        .eq(SettlementAdjustmentEntity::getPeriod, period)
                        .eq(SettlementAdjustmentEntity::getStatus, AdjustmentStatus.ACTIVE.name()))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public SettlementAdjustment save(SettlementAdjustment adjustment) {
        if (adjustment.getId() == null) {
            SettlementAdjustmentEntity entity = toEntity(adjustment);
            mapper.insert(entity);
            return SettlementAdjustment.rehydrate(entity.getId(), entity.getVersion(),
                    adjustment.getIdempotencyKey(), adjustment.getMerchantId(), adjustment.getPeriod(),
                    adjustment.getAmountMinor(), adjustment.getDirection(), adjustment.getCurrencyCode(),
                    adjustment.getReason(), adjustment.getOperator(), adjustment.getStatus(),
                    adjustment.getCreatedAt());
        }
        mapper.updateById(toEntity(adjustment));
        return adjustment;
    }

    private SettlementAdjustment toDomain(SettlementAdjustmentEntity e) {
        return SettlementAdjustment.rehydrate(e.getId(), e.getVersion(), e.getIdempotencyKey(),
                e.getMerchantId(), e.getPeriod(), e.getAmountMinor(),
                AdjustmentDirection.valueOf(e.getDirection()), e.getCurrencyCode(),
                e.getReason(), e.getOperator(), AdjustmentStatus.valueOf(e.getStatus()),
                e.getCreatedAt());
    }

    private SettlementAdjustmentEntity toEntity(SettlementAdjustment a) {
        SettlementAdjustmentEntity e = new SettlementAdjustmentEntity();
        e.setId(a.getId());
        e.setVersion(a.getVersion());
        e.setIdempotencyKey(a.getIdempotencyKey());
        e.setMerchantId(a.getMerchantId());
        e.setPeriod(a.getPeriod());
        e.setAmountMinor(a.getAmountMinor());
        e.setDirection(a.getDirection().name());
        e.setCurrencyCode(a.getCurrencyCode());
        e.setReason(a.getReason());
        e.setOperator(a.getOperator());
        e.setStatus(a.getStatus().name());
        e.setCreatedAt(a.getCreatedAt());
        return e;
    }
}
