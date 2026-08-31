package com.payment.catalog.domain;

import java.util.Optional;

/**
 * 库存仓储端口。
 */
public interface StockRepository {

    Optional<Stock> findBySkuId(Long skuId);

    Stock save(Stock stock);
}
