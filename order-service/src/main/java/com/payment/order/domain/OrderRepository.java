package com.payment.order.domain;

import java.util.Optional;

/**
 * 订单仓储边界（领域接口，不依赖持久化实现）。
 */
public interface OrderRepository {

    Optional<Order> findById(Long id);

    Order save(Order order);
}
