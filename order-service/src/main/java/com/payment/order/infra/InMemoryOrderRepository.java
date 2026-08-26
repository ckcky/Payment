package com.payment.order.infra;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * 内存订单仓储（MVP）。
 */
@Repository
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
