package com.payment.order.domain;

import java.util.Optional;

/**
 * 订单仓储边界（领域接口，不依赖持久化实现）。
 */
public interface OrderRepository {

    Optional<Order> findById(Long id);
    /** 按业务单号查询（跨系统引用一律用 orderNo，ADR-0063）。 */
    Optional<Order> findByOrderNo(String orderNo);

    Order save(Order order);
}
