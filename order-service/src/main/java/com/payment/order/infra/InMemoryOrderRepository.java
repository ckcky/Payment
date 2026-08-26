package com.payment.order.infra;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存订单仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisOrderRepository} 承接。
 */
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<Long, Order> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Order save(Order order) {
        if (order.getId() == null) {
            order.setId(idGen.incrementAndGet());
        }
        byId.put(order.getId(), order);
        return order;
    }
}
