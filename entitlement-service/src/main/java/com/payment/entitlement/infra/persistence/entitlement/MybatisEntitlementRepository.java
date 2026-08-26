package com.payment.entitlement.infra.persistence.entitlement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import com.payment.entitlement.domain.EntitlementStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 权益仓储 MyBatis 实现：领域对象与 PO 双向映射，落到自有 Schema。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖。{@code sourceFulfillmentId} 为幂等键，由表上唯一索引兜底。</p>
 */
@Repository
public class MybatisEntitlementRepository implements EntitlementRepository {

    private final EntitlementMapper entitlementMapper;

    public MybatisEntitlementRepository(EntitlementMapper entitlementMapper) {
        this.entitlementMapper = entitlementMapper;
    }

    @Override
    public Optional<Entitlement> findById(Long id) {
        EntitlementEntity entity = entitlementMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Entitlement> findBySourceFulfillmentId(String sourceFulfillmentId) {
        EntitlementEntity entity = entitlementMapper.selectOne(
                Wrappers.<EntitlementEntity>lambdaQuery()
                        .eq(EntitlementEntity::getSourceFulfillmentId, sourceFulfillmentId));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<Entitlement> findByOrderId(String orderId) {
        return entitlementMapper.selectList(
                        Wrappers.<EntitlementEntity>lambdaQuery()
                                .eq(EntitlementEntity::getOrderId, orderId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Entitlement save(Entitlement entitlement) {
        if (entitlement.getId() == null) {
            EntitlementEntity entity = toEntity(entitlement);
            entitlementMapper.insert(entity);
            entitlement.setId(entity.getId());
            entitlement.setVersion(entity.getVersion());
            return entitlement;
        }
        EntitlementEntity entity = toEntity(entitlement);
        if (entitlementMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "entitlement concurrent update: " + entitlement.getId());
        }
        entitlement.setVersion(entitlement.getVersion() + 1);
        return entitlement;
    }

    private Entitlement toDomain(EntitlementEntity entity) {
        return Entitlement.rehydrate(entity.getId(), entity.getUserId(), entity.getOrderId(),
                entity.getSourceFulfillmentId(), entity.getAvailableQuantity(), entity.getScope(),
                entity.getExpiryAt(), EntitlementStatus.valueOf(entity.getStatus()), entity.getVersion(),
                entity.getGrantRef());
    }

    private EntitlementEntity toEntity(Entitlement entitlement) {
        EntitlementEntity entity = new EntitlementEntity();
        entity.setId(entitlement.getId());
        entity.setUserId(entitlement.getUserId());
        entity.setOrderId(entitlement.getOrderId());
        entity.setSourceFulfillmentId(entitlement.getSourceFulfillmentId());
        entity.setGrantRef(entitlement.getGrantRef());
        entity.setAvailableQuantity(entitlement.getAvailableQuantity());
        entity.setScope(entitlement.getScope());
        entity.setExpiryAt(entitlement.getExpiryAt());
        entity.setStatus(entitlement.getStatus().name());
        entity.setVersion(entitlement.getVersion());
        return entity;
    }
}
