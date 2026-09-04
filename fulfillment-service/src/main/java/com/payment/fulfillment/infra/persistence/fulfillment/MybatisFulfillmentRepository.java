package com.payment.fulfillment.infra.persistence.fulfillment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import com.payment.fulfillment.domain.FulfillmentStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 履约仓储 MyBatis 实现（T045d）：履约聚合落到自有 Schema，领域对象与 PO 双向映射。
 *
 * <p>{@code sourcePaymentNo} 带 UNIQUE 约束保证同一支付成功事件只创建一条履约（幂等）；
 * 更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}。</p>
 */
@Repository
public class MybatisFulfillmentRepository implements FulfillmentRepository {

    private final FulfillmentMapper fulfillmentMapper;

    public MybatisFulfillmentRepository(FulfillmentMapper fulfillmentMapper) {
        this.fulfillmentMapper = fulfillmentMapper;
    }

    @Override
    public Optional<Fulfillment> findById(Long id) {
        FulfillmentEntity entity = fulfillmentMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Fulfillment> findBySourcePaymentNo(String sourcePaymentNo) {
        FulfillmentEntity entity = fulfillmentMapper.selectOne(
                Wrappers.<FulfillmentEntity>lambdaQuery()
                        .eq(FulfillmentEntity::getSourcePaymentNo, sourcePaymentNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Fulfillment> findByOrderNo(String orderNo) {
        FulfillmentEntity entity = fulfillmentMapper.selectOne(
                Wrappers.<FulfillmentEntity>lambdaQuery().eq(FulfillmentEntity::getOrderNo, orderNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Fulfillment save(Fulfillment fulfillment) {
        if (fulfillment.getId() == null) {
            FulfillmentEntity entity = toEntity(fulfillment);
            fulfillmentMapper.insert(entity);
            fulfillment.setId(entity.getId());
            fulfillment.setVersion(entity.getVersion());
            return fulfillment;
        }
        FulfillmentEntity entity = toEntity(fulfillment);
        if (fulfillmentMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "fulfillment concurrent update: " + fulfillment.getId());
        }
        fulfillment.setVersion(fulfillment.getVersion() + 1);
        return fulfillment;
    }

    private Fulfillment toDomain(FulfillmentEntity entity) {
        return Fulfillment.rehydrate(entity.getId(), entity.getOrderNo(), entity.getOrderItemId(),
                entity.getDeliveryContent(), entity.getSourcePaymentNo(),
                FulfillmentStatus.valueOf(entity.getStatus()), entity.getFailureReason(), entity.getVersion());
    }

    private FulfillmentEntity toEntity(Fulfillment fulfillment) {
        FulfillmentEntity entity = new FulfillmentEntity();
        entity.setId(fulfillment.getId());
        entity.setOrderNo(fulfillment.getOrderNo());
        entity.setOrderItemId(fulfillment.getOrderItemId());
        entity.setDeliveryContent(fulfillment.getDeliveryContent());
        entity.setSourcePaymentNo(fulfillment.getSourcePaymentNo());
        entity.setStatus(fulfillment.getStatus().name());
        entity.setFailureReason(fulfillment.getFailureReason());
        entity.setVersion(fulfillment.getVersion());
        return entity;
    }
}
