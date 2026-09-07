package com.payment.order.infra.persistence.transaction;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.RefundOrderStatus;
import com.payment.order.domain.TransactionRefundRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交易层退款单仓储 MyBatis 实现（spec 019 / T103）：幂等键寻址 + 更新走乐观锁。
 */
@Repository
public class MybatisTransactionRefundRepository implements TransactionRefundRepository {

    private final TransactionRefundMapper mapper;

    public MybatisTransactionRefundRepository(TransactionRefundMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RefundOrder save(RefundOrder refundOrder) {
        if (refundOrder.getId() == null) {
            TransactionRefundEntity entity = toEntity(refundOrder);
            mapper.insert(entity);
            refundOrder.setId(entity.getId());
            refundOrder.setVersion(entity.getVersion());
            return refundOrder;
        }
        TransactionRefundEntity entity = toEntity(refundOrder);
        if (mapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT,
                    "transaction refund concurrent update: " + refundOrder.getId());
        }
        refundOrder.setVersion(refundOrder.getVersion() + 1);
        return refundOrder;
    }

    @Override
    public Optional<RefundOrder> findById(Long id) {
        TransactionRefundEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<RefundOrder> findByRefundNo(String refundNo) {
        TransactionRefundEntity entity = mapper.selectOne(
                Wrappers.<TransactionRefundEntity>lambdaQuery()
                        .eq(TransactionRefundEntity::getRefundNo, refundNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<RefundOrder> findByIdempotencyKey(String idempotencyKey) {
        TransactionRefundEntity entity = mapper.selectOne(
                Wrappers.<TransactionRefundEntity>lambdaQuery()
                        .eq(TransactionRefundEntity::getIdempotencyKey, idempotencyKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<RefundOrder> findByTransactionNo(String transactionNo) {
        return mapper.selectList(Wrappers.<TransactionRefundEntity>lambdaQuery()
                        .eq(TransactionRefundEntity::getTransactionNo, transactionNo))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<RefundOrder> findByOrderNo(String orderNo) {
        return mapper.selectList(Wrappers.<TransactionRefundEntity>lambdaQuery()
                        .eq(TransactionRefundEntity::getOrderNo, orderNo))
                .stream().map(this::toDomain).toList();
    }

    private RefundOrder toDomain(TransactionRefundEntity entity) {
        return RefundOrder.rehydrate(entity.getId(), entity.getRefundNo(), entity.getPaymentRefundNo(),
                entity.getTransactionNo(), entity.getOrderNo(), entity.getPaymentNo(), entity.getUserId(),
                entity.getAmountMinor(), entity.getCurrencyCode(),
                RefundOrderStatus.valueOf(entity.getStatus()), entity.getReason(), entity.getVersion());
    }

    private TransactionRefundEntity toEntity(RefundOrder refundOrder) {
        TransactionRefundEntity entity = new TransactionRefundEntity();
        entity.setId(refundOrder.getId());
        entity.setRefundNo(refundOrder.getRefundNo());
        entity.setPaymentRefundNo(refundOrder.getPaymentRefundNo());
        entity.setTransactionNo(refundOrder.getTransactionNo());
        entity.setOrderNo(refundOrder.getOrderNo());
        entity.setPaymentNo(refundOrder.getPaymentNo());
        entity.setUserId(refundOrder.getUserId());
        entity.setAmountMinor(refundOrder.getAmountMinor());
        entity.setCurrencyCode(refundOrder.getCurrencyCode());
        entity.setStatus(refundOrder.getStatus().name());
        entity.setReason(refundOrder.getReason());
        entity.setIdempotencyKey(refundOrder.getIdempotencyKey());
        entity.setVersion(refundOrder.getVersion());
        return entity;
    }
}
