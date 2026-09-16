package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.payment.limit.domain.LimitOperation;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 额度操作流水仓储 MyBatis 实现（spec 027 / INV-4）。
 *
 * <p><b>幂等是数据库级的</b>：{@link #insert} 撞 {@code UK(biz_no, op_type, period)} 时捕获
 * {@link DuplicateKeyException} 并返回 {@code false}，调用方据此跳过金额变更。
 * 这是三道闸门的第 2 道，挡「事务外重试」与「补偿重跑」。</p>
 */
@Repository
public class MybatisLimitOperationRepository implements LimitOperationRepository {

    private final LimitOperationMapper mapper;

    public MybatisLimitOperationRepository(LimitOperationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insert(LimitOperation operation) {
        LimitOperationEntity entity = new LimitOperationEntity();
        entity.setOperationNo(operation.operationNo());
        entity.setBizNo(operation.bizNo());
        entity.setOpType(operation.opType().name());
        entity.setUserId(operation.userId());
        entity.setCurrencyCode(operation.currencyCode());
        entity.setPeriod(operation.period().name());
        entity.setAmountMinor(operation.amountMinor());
        entity.setExpiresAt(operation.expiresAt());
        try {
            mapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // 撞 UK(biz_no, op_type, period)：该周期上的该操作已发生过，跳过金额变更是正确行为
            return false;
        }
    }

    @Override
    public boolean exists(String bizNo, LimitOperationType opType, LimitPeriod period) {
        var query = Wrappers.<LimitOperationEntity>lambdaQuery()
                .eq(LimitOperationEntity::getBizNo, bizNo)
                .eq(LimitOperationEntity::getOpType, opType.name());
        if (period != null) {
            query.eq(LimitOperationEntity::getPeriod, period.name());
        }
        return mapper.selectCount(query) > 0;
    }

    @Override
    public List<LimitOperation> findByBizNo(String bizNo) {
        return mapper.selectList(Wrappers.<LimitOperationEntity>lambdaQuery()
                        .eq(LimitOperationEntity::getBizNo, bizNo)
                        .orderByAsc(LimitOperationEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<LimitOperation> find(String bizNo, LimitOperationType opType, LimitPeriod period) {
        var query = Wrappers.<LimitOperationEntity>lambdaQuery()
                .eq(LimitOperationEntity::getBizNo, bizNo)
                .eq(LimitOperationEntity::getOpType, opType.name());
        if (period != null) {
            query.eq(LimitOperationEntity::getPeriod, period.name());
        }
        LimitOperationEntity entity = mapper.selectOne(query);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public void delete(LimitOperation operation) {
        if (operation.id() != null) {
            mapper.deleteById(operation.id());
            return;
        }
        var query = Wrappers.<LimitOperationEntity>lambdaQuery()
                .eq(LimitOperationEntity::getBizNo, operation.bizNo())
                .eq(LimitOperationEntity::getOpType, operation.opType().name());
        if (operation.period() != null) {
            query.eq(LimitOperationEntity::getPeriod, operation.period().name());
        }
        mapper.delete(query);
    }

    /**
     * 未结算在途（FR-038 惰性回收用）：该用户所有 {@code RESERVE} 且无同 {@code biz_no} 终态流水的记录。
     *
     * <p>走 {@code idx_limitop_user_type}；结果集天然极小（同一用户未终态的在途通常 0~几条），
     * 因此这里允许「先查后判」——判定的对象是 Redis key 是否还在，不是限额本身。</p>
     */
    @Override
    public List<LimitOperation> findUnsettledReserves(String userId) {
        return mapper.selectList(Wrappers.<LimitOperationEntity>lambdaQuery()
                        .eq(LimitOperationEntity::getUserId, userId)
                        .eq(LimitOperationEntity::getOpType, LimitOperationType.RESERVE.name())
                        .notExists("SELECT 1 FROM limit_operations s WHERE s.biz_no = limit_operations.biz_no"
                                + " AND s.op_type IN ('CONFIRM','RELEASE','EXPIRED')"))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private LimitOperation toDomain(LimitOperationEntity e) {
        return new LimitOperation(e.getId(), e.getOperationNo(), e.getBizNo(),
                LimitOperationType.valueOf(e.getOpType()), e.getUserId(), e.getCurrencyCode(),
                LimitPeriod.parse(e.getPeriod()), nz(e.getAmountMinor()),
                e.getExpiresAt(), toInstant(e.getCreatedAt()));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static java.time.Instant toInstant(LocalDateTime dt) {
        return dt == null ? null : dt.toInstant(ZoneOffset.UTC);
    }
}
