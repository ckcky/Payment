package com.payment.payment.infra.persistence.attempt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 支付尝试仓储 MyBatis 实现：每次渠道交互独立落表（渠道引用唯一兜底），领域对象与 PO 双向映射。
 * 更新走乐观锁，冲突抛 {@link ErrorCodes#CONFLICT}。
 */
@Repository
public class MybatisPaymentAttemptRepository implements PaymentAttemptRepository {

    private final PaymentAttemptMapper attemptMapper;

    public MybatisPaymentAttemptRepository(PaymentAttemptMapper attemptMapper) {
        this.attemptMapper = attemptMapper;
    }

    @Override
    public Optional<PaymentAttempt> findById(Long id) {
        PaymentAttemptEntity entity = attemptMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<PaymentAttempt> findByPaymentId(Long paymentId) {
        return attemptMapper.selectList(
                        Wrappers.<PaymentAttemptEntity>lambdaQuery()
                                .eq(PaymentAttemptEntity::getPaymentId, paymentId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        if (attempt.getId() == null) {
            PaymentAttemptEntity entity = toEntity(attempt);
            attemptMapper.insert(entity);
            attempt.setId(entity.getId());
            attempt.setVersion(entity.getVersion());
            return attempt;
        }
        PaymentAttemptEntity entity = toEntity(attempt);
        if (attemptMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "payment attempt concurrent update: " + attempt.getId());
        }
        attempt.setVersion(attempt.getVersion() + 1);
        return attempt;
    }

    private PaymentAttempt toDomain(PaymentAttemptEntity entity) {
        return PaymentAttempt.rehydrate(entity.getId(), entity.getPaymentId(), entity.getChannelCode(),
                entity.getRetryCount(), entity.getRequestedAt(), entity.getRespondedAt(),
                entity.getChannelReference(), PaymentAttemptStatus.valueOf(entity.getStatus()),
                entity.getFailureReason(), entity.getVersion());
    }

    private PaymentAttemptEntity toEntity(PaymentAttempt attempt) {
        PaymentAttemptEntity entity = new PaymentAttemptEntity();
        entity.setId(attempt.getId());
        entity.setPaymentId(attempt.getPaymentId());
        entity.setChannelCode(attempt.getChannelCode());
        entity.setRequestedAt(attempt.getRequestedAt());
        entity.setRespondedAt(attempt.getRespondedAt());
        entity.setChannelReference(attempt.getChannelReference());
        entity.setStatus(attempt.getStatus().name());
        entity.setFailureReason(attempt.getFailureReason());
        entity.setRetryCount(attempt.getRetryCount());
        entity.setVersion(attempt.getVersion());
        return entity;
    }
}
