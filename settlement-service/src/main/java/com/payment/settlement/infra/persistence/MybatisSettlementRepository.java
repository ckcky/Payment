package com.payment.settlement.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementItem;
import com.payment.settlement.domain.SettlementRepository;
import com.payment.settlement.domain.SettlementStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 结算仓储 MyBatis 实现：结算批次落到自有 Schema，明细作为 1:N 值对象随聚合读写。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖（资金正确性）。幂等键与商户+周期由数据库唯一约束兜底。</p>
 */
@Repository
public class MybatisSettlementRepository implements SettlementRepository {

    private final SettlementBatchMapper batchMapper;
    private final SettlementItemMapper itemMapper;

    public MybatisSettlementRepository(SettlementBatchMapper batchMapper, SettlementItemMapper itemMapper) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public Optional<SettlementBatch> findById(Long id) {
        SettlementBatchEntity entity = batchMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<SettlementBatch> findByMerchantAndPeriod(String merchantId, String period) {
        SettlementBatchEntity entity = batchMapper.selectOne(Wrappers.<SettlementBatchEntity>lambdaQuery()
                .eq(SettlementBatchEntity::getMerchantId, merchantId)
                .eq(SettlementBatchEntity::getPeriod, period));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public SettlementBatch save(SettlementBatch batch) {
        if (batch.getId() == null) {
            SettlementBatchEntity entity = toEntity(batch);
            batchMapper.insert(entity);
            batch.setId(entity.getId());
            batch.setVersion(entity.getVersion());
            insertItems(batch.getId(), batch.getItems());
            return batch;
        }
        SettlementBatchEntity entity = toEntity(batch);
        if (batchMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "settlement batch concurrent update: " + batch.getId());
        }
        batch.setVersion(batch.getVersion() + 1);
        return batch;
    }

    private void insertItems(Long batchId, List<SettlementItem> items) {
        for (SettlementItem item : items) {
            SettlementItemEntity entity = new SettlementItemEntity();
            entity.setBatchId(batchId);
            entity.setReference(item.reference());
            entity.setType(item.type());
            entity.setAmountMinor(item.amountMinor());
            entity.setCurrencyCode(item.currencyCode());
            itemMapper.insert(entity);
        }
    }

    private List<SettlementItem> loadItems(Long batchId) {
        return itemMapper.selectList(Wrappers.<SettlementItemEntity>lambdaQuery()
                        .eq(SettlementItemEntity::getBatchId, batchId))
                .stream()
                .map(e -> new SettlementItem(e.getReference(), e.getType(), e.getAmountMinor(), e.getCurrencyCode()))
                .toList();
    }

    private SettlementBatch toDomain(SettlementBatchEntity entity) {
        return SettlementBatch.rehydrate(entity.getId(), entity.getMerchantId(), entity.getPeriod(),
                entity.getCurrencyCode(), entity.getIncomeMinor(), entity.getRefundMinor(),
                entity.getAdjustmentMinor(), entity.getNetMinor(),
                SettlementStatus.valueOf(entity.getStatus()), loadItems(entity.getId()),
                entity.getIdempotencyKey(), entity.getVersion());
    }

    private SettlementBatchEntity toEntity(SettlementBatch batch) {
        SettlementBatchEntity entity = new SettlementBatchEntity();
        entity.setId(batch.getId());
        entity.setMerchantId(batch.getMerchantId());
        entity.setPeriod(batch.getPeriod());
        entity.setCurrencyCode(batch.getCurrencyCode());
        entity.setIncomeMinor(batch.getIncomeMinor());
        entity.setRefundMinor(batch.getRefundMinor());
        entity.setAdjustmentMinor(batch.getAdjustmentMinor());
        entity.setNetMinor(batch.getNetMinor());
        entity.setStatus(batch.getStatus().name());
        entity.setIdempotencyKey(batch.getIdempotencyKey());
        entity.setVersion(batch.getVersion());
        return entity;
    }
}
