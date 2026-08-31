package com.payment.catalog.domain;

import java.util.List;
import java.util.Optional;

/**
 * Sku 仓储接口（领域层，无持久化技术依赖）。
 */
public interface SkuRepository {

    Optional<Sku> findById(Long id);

    Optional<Sku> findByCode(String skuCode);

    List<Sku> findAll();

    Sku save(Sku sku);
}
