package com.payment.catalog.domain;

import java.util.Optional;

/**
 * Product 仓储接口（领域层，无持久化技术依赖）。
 */
public interface ProductRepository {

    Optional<Product> findById(Long id);

    Optional<Product> findByCode(String productCode);

    Product save(Product product);
}
