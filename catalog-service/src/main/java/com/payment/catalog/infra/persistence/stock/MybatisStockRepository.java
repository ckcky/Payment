package com.payment.catalog.infra.persistence.stock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.catalog.domain.Stock;
import com.payment.catalog.domain.StockRepository;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 库存仓储 MyBatis 实现：按 skuId 查询；更新走乐观锁（version 不匹配 → CONFLICT）。
 */
@Repository
public class MybatisStockRepository implements StockRepository {

    private final StockMapper stockMapper;

    public MybatisStockRepository(StockMapper stockMapper) {
        this.stockMapper = stockMapper;
    }

    @Override
    public Optional<Stock> findBySkuId(Long skuId) {
        StockEntity entity = stockMapper.selectOne(
                Wrappers.<StockEntity>lambdaQuery().eq(StockEntity::getSkuId, skuId));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Stock save(Stock stock) {
        if (stock.getId() == null) {
            StockEntity entity = toEntity(stock);
            stockMapper.insert(entity);
            stock.setId(entity.getId());
            stock.setVersion(entity.getVersion());
            return stock;
        }
        StockEntity entity = toEntity(stock);
        if (stockMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "stock concurrent update sku=" + stock.getSkuId());
        }
        stock.setVersion(stock.getVersion() + 1);
        return stock;
    }

    private Stock toDomain(StockEntity entity) {
        return Stock.rehydrate(entity.getId(), entity.getSkuId(), entity.getTotal(),
                entity.getAvailable(), entity.getReserved(), entity.getSold(), entity.getVersion());
    }

    private StockEntity toEntity(Stock stock) {
        StockEntity entity = new StockEntity();
        entity.setId(stock.getId());
        entity.setSkuId(stock.getSkuId());
        entity.setTotal(stock.getTotal());
        entity.setAvailable(stock.getAvailable());
        entity.setReserved(stock.getReserved());
        entity.setSold(stock.getSold());
        entity.setVersion(stock.getVersion());
        return entity;
    }
}
