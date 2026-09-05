package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundItem;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 退款仓储 MyBatis 实现：退款聚合落到自有 Schema，明细作为 1:N 值对象随聚合读写。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖（资金正确性）。幂等键由数据库唯一约束兜底。</p>
 */
@Repository
public class MybatisRefundRepository implements RefundRepository {

    private final RefundMapper refundMapper;
    private final RefundItemMapper refundItemMapper;
    private final RefundIntakeLockMapper intakeLockMapper;

    public MybatisRefundRepository(RefundMapper refundMapper, RefundItemMapper refundItemMapper,
                                   RefundIntakeLockMapper intakeLockMapper) {
        this.refundMapper = refundMapper;
        this.refundItemMapper = refundItemMapper;
        this.intakeLockMapper = intakeLockMapper;
    }

    @Override
    public Optional<Refund> findById(Long id) {
        RefundEntity entity = refundMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
        RefundEntity entity = refundMapper.selectOne(
                Wrappers.<RefundEntity>lambdaQuery().eq(RefundEntity::getIdempotencyKey, idempotencyKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Refund> findByRefundNo(String refundNo) {
        RefundEntity entity = refundMapper.selectOne(
                Wrappers.<RefundEntity>lambdaQuery().eq(RefundEntity::getRefundNo, refundNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<Refund> findByPaymentNo(String paymentNo) {
        return refundMapper.selectList(
                        Wrappers.<RefundEntity>lambdaQuery().eq(RefundEntity::getPaymentNo, paymentNo))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Refund> findByOrderNo(String orderNo) {
        return refundMapper.selectList(
                        Wrappers.<RefundEntity>lambdaQuery().eq(RefundEntity::getOrderNo, orderNo))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Refund> findByStatus(RefundStatus status) {
        return refundMapper.selectList(
                        Wrappers.<RefundEntity>lambdaQuery().eq(RefundEntity::getStatus, status.name()))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void lockForIntake(String paymentNo) {
        intakeLockMapper.lockForIntake(paymentNo);
    }

    @Override
    public Refund save(Refund refund) {
        if (refund.getId() == null) {
            RefundEntity entity = toEntity(refund);
            refundMapper.insert(entity);
            refund.setId(entity.getId());
            refund.setVersion(entity.getVersion());
            insertItems(refund.getRefundNo(), refund.getItems());
            return refund;
        }
        RefundEntity entity = toEntity(refund);
        if (refundMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "refund concurrent update: " + refund.getId());
        }
        refund.setVersion(refund.getVersion() + 1);
        return refund;
    }

    private void insertItems(String refundNo, List<RefundItem> items) {
        for (RefundItem item : items) {
            RefundItemEntity entity = new RefundItemEntity();
            entity.setRefundNo(refundNo);
            entity.setOrderItemId(item.orderItemId());
            entity.setAmountMinor(item.amountMinor());
            refundItemMapper.insert(entity);
        }
    }

    private List<RefundItem> loadItems(String refundNo) {
        return refundItemMapper.selectList(
                        Wrappers.<RefundItemEntity>lambdaQuery().eq(RefundItemEntity::getRefundNo, refundNo))
                .stream()
                .map(e -> new RefundItem(e.getOrderItemId(), e.getAmountMinor()))
                .toList();
    }

    private Refund toDomain(RefundEntity entity) {
        return Refund.rehydrate(entity.getId(), entity.getRefundNo(), entity.getOrderNo(), entity.getPaymentNo(),
                entity.getUserId(), entity.getAmountMinor(), entity.getCurrencyCode(),
                entity.getReason(), entity.getIdempotencyKey(), loadItems(entity.getRefundNo()),
                RefundStatus.valueOf(entity.getStatus()), entity.getFailureReason(), entity.getVersion());
    }

    private RefundEntity toEntity(Refund refund) {
        RefundEntity entity = new RefundEntity();
        entity.setId(refund.getId());
        entity.setRefundNo(refund.getRefundNo());
        entity.setOrderNo(refund.getOrderNo());
        entity.setPaymentNo(refund.getPaymentNo());
        entity.setUserId(refund.getUserId());
        entity.setAmountMinor(refund.getAmountMinor());
        entity.setCurrencyCode(refund.getCurrencyCode());
        entity.setReason(refund.getReason());
        entity.setIdempotencyKey(refund.getIdempotencyKey());
        entity.setStatus(refund.getStatus().name());
        entity.setFailureReason(refund.getFailureReason());
        entity.setVersion(refund.getVersion());
        return entity;
    }
}
