package com.payment.catalog.infra.persistence.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.catalog.domain.Product;
import com.payment.catalog.domain.ProductRepository;
import com.payment.catalog.domain.ProductStatus;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 商品仓储 MyBatis 实现：商品聚合落到自有 Schema，领域对象与 PO 双向映射。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖。</p>
 */
@Repository
public class MybatisProductRepository implements ProductRepository {

    private final ProductMapper productMapper;

    public MybatisProductRepository(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    public Optional<Product> findById(Long id) {
        ProductEntity entity = productMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Product> findByCode(String productCode) {
        ProductEntity entity = productMapper.selectOne(
                Wrappers.<ProductEntity>lambdaQuery().eq(ProductEntity::getProductCode, productCode));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Product save(Product product) {
        if (product.getId() == null) {
            ProductEntity entity = toEntity(product);
            productMapper.insert(entity);
            product.setId(entity.getId());
            product.setVersion(entity.getVersion());
            return product;
        }
        ProductEntity entity = toEntity(product);
        if (productMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "product concurrent update: " + product.getId());
        }
        product.setVersion(product.getVersion() + 1);
        return product;
    }

    private Product toDomain(ProductEntity entity) {
        return Product.rehydrate(entity.getId(), entity.getProductCode(), entity.getName(),
                entity.getType(), ProductStatus.valueOf(entity.getStatus()), entity.getVersion());
    }

    private ProductEntity toEntity(Product product) {
        ProductEntity entity = new ProductEntity();
        entity.setId(product.getId());
        entity.setProductCode(product.getProductCode());
        entity.setName(product.getName());
        entity.setType(product.getType());
        entity.setStatus(product.getStatus().name());
        entity.setVersion(product.getVersion());
        return entity;
    }
}
