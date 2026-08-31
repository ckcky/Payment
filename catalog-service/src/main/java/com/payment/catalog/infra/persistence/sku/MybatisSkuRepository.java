package com.payment.catalog.infra.persistence.sku;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.catalog.domain.Sku;
import com.payment.catalog.domain.SkuRepository;
import com.payment.catalog.domain.SkuStatus;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * SKU 仓储 MyBatis 实现：SKU 聚合落到自有 Schema，按编码查询；更新走乐观锁。
 */
@Repository
public class MybatisSkuRepository implements SkuRepository {

    private final SkuMapper skuMapper;

    public MybatisSkuRepository(SkuMapper skuMapper) {
        this.skuMapper = skuMapper;
    }

    @Override
    public Optional<Sku> findById(Long id) {
        SkuEntity entity = skuMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Sku> findByCode(String skuCode) {
        SkuEntity entity = skuMapper.selectOne(
                Wrappers.<SkuEntity>lambdaQuery().eq(SkuEntity::getSkuCode, skuCode));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<Sku> findAll() {
        return skuMapper.selectList(null).stream().map(this::toDomain).toList();
    }

    @Override
    public Sku save(Sku sku) {
        if (sku.getId() == null) {
            SkuEntity entity = toEntity(sku);
            skuMapper.insert(entity);
            sku.setId(entity.getId());
            sku.setVersion(entity.getVersion());
            return sku;
        }
        SkuEntity entity = toEntity(sku);
        if (skuMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "sku concurrent update: " + sku.getId());
        }
        sku.setVersion(sku.getVersion() + 1);
        return sku;
    }

    private Sku toDomain(SkuEntity entity) {
        return Sku.rehydrate(entity.getId(), entity.getSkuCode(), entity.getProductId(),
                entity.getName(), entity.getPriceMinor(), entity.getCurrencyCode(),
                entity.getDeliveryDefinition(), SkuStatus.valueOf(entity.getStatus()),
                entity.getVersion());
    }

    private SkuEntity toEntity(Sku sku) {
        SkuEntity entity = new SkuEntity();
        entity.setId(sku.getId());
        entity.setSkuCode(sku.getSkuCode());
        entity.setProductId(sku.getProductId());
        entity.setName(sku.getName());
        entity.setPriceMinor(sku.getPriceMinor());
        entity.setCurrencyCode(sku.getCurrencyCode());
        entity.setDeliveryDefinition(sku.getDeliveryDefinition());
        entity.setStatus(sku.getStatus().name());
        entity.setVersion(sku.getVersion());
        return entity;
    }
}
