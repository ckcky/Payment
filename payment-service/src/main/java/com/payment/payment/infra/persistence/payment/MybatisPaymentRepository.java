package com.payment.payment.infra.persistence.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 支付仓储 MyBatis 实现：支付意图落到自有 Schema，领域对象与 PO 双向映射。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖（资金正确性）。幂等键由数据库唯一约束兜底。</p>
 */
@Repository
public class MybatisPaymentRepository implements PaymentRepository {

    private final PaymentMapper paymentMapper;

    public MybatisPaymentRepository(PaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
    }

    @Override
    public Optional<Payment> findById(Long id) {
        PaymentEntity entity = paymentMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Payment> findByTransactionId(String transactionId) {
        PaymentEntity entity = paymentMapper.selectOne(
                Wrappers.<PaymentEntity>lambdaQuery().eq(PaymentEntity::getTransactionId, transactionId));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        PaymentEntity entity = paymentMapper.selectOne(
                Wrappers.<PaymentEntity>lambdaQuery().eq(PaymentEntity::getIdempotencyKey, idempotencyKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<Payment> findByStatus(PaymentStatus status) {
        return paymentMapper.selectList(
                        Wrappers.<PaymentEntity>lambdaQuery().eq(PaymentEntity::getStatus, status.name()))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Payment save(Payment payment) {
        if (payment.getId() == null) {
            PaymentEntity entity = toEntity(payment);
            paymentMapper.insert(entity);
            payment.setId(entity.getId());
            payment.setVersion(entity.getVersion());
            return payment;
        }
        PaymentEntity entity = toEntity(payment);
        if (paymentMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "payment concurrent update: " + payment.getId());
        }
        payment.setVersion(payment.getVersion() + 1);
        return payment;
    }

    private Payment toDomain(PaymentEntity entity) {
        return Payment.rehydrate(entity.getId(), entity.getPaymentNo(), entity.getTransactionId(), entity.getOrderId(),
                entity.getUserId(), entity.getAmountMinor(), entity.getCurrencyCode(),
                entity.getIdempotencyKey(), PaymentStatus.valueOf(entity.getStatus()),
                entity.getCurrentAttemptId(), entity.getFailureReason(),
                entity.getQueryAttempts() != null ? entity.getQueryAttempts() : 0,
                entity.getEnteredUnknownAt(), entity.getVersion());
    }

    private PaymentEntity toEntity(Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(payment.getId());
        entity.setPaymentNo(payment.getPaymentNo());
        entity.setTransactionId(payment.getTransactionId());
        entity.setOrderId(payment.getOrderId());
        entity.setUserId(payment.getUserId());
        entity.setAmountMinor(payment.getAmountMinor());
        entity.setCurrencyCode(payment.getCurrencyCode());
        entity.setIdempotencyKey(payment.getIdempotencyKey());
        entity.setStatus(payment.getStatus().name());
        entity.setCurrentAttemptId(payment.getCurrentAttemptId());
        entity.setFailureReason(payment.getFailureReason());
        entity.setQueryAttempts(payment.getQueryAttempts());
        entity.setEnteredUnknownAt(payment.getEnteredUnknownAt());
        entity.setVersion(payment.getVersion());
        return entity;
    }
}
