package com.payment.order.infra.persistence.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 订单仓储 MyBatis 实现（T045b）：订单聚合 + 明细快照落到自有 Schema，领域对象与 PO 双向映射。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖。</p>
 */
@Repository
public class MybatisOrderRepository implements OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public MybatisOrderRepository(OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Override
    public Optional<Order> findByOrderNo(String orderNo) {
        OrderEntity entity = orderMapper.selectOne(
                Wrappers.<OrderEntity>lambdaQuery().eq(OrderEntity::getOrderNo, orderNo));
        if (entity == null) {
            return Optional.empty();
        }
        List<OrderItemEntity> itemEntities = orderItemMapper.selectList(
                Wrappers.<OrderItemEntity>lambdaQuery().eq(OrderItemEntity::getOrderNo, orderNo));
        return Optional.of(toDomain(entity, itemEntities));
    }

    @Override
    public Optional<Order> findById(Long id) {
        OrderEntity entity = orderMapper.selectById(id);
        if (entity == null) {
            return Optional.empty();
        }
        List<OrderItemEntity> itemEntities = orderItemMapper.selectList(
                Wrappers.<OrderItemEntity>lambdaQuery().eq(OrderItemEntity::getOrderNo, entity.getOrderNo()));
        return Optional.of(toDomain(entity, itemEntities));
    }

    @Override
    public Order save(Order order) {
        if (order.getId() == null) {
            OrderEntity entity = toEntity(order);
            orderMapper.insert(entity);
            order.setId(entity.getId());
            order.setVersion(entity.getVersion());
            for (OrderItem item : order.getItems()) {
                orderItemMapper.insert(toItemEntity(entity.getOrderNo(), item));
            }
            return order;
        }
        OrderEntity entity = toEntity(order);
        if (orderMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "order concurrent update: " + order.getId());
        }
        order.setVersion(order.getVersion() + 1);
        return order;
    }

    private Order toDomain(OrderEntity entity, List<OrderItemEntity> itemEntities) {
        List<OrderItem> items = itemEntities.stream()
                .map(this::toItem)
                .toList();
        return Order.rehydrate(entity.getId(), entity.getOrderNo(), entity.getUserId(), entity.getMerchantId(),
                entity.getPaymentNo(), OrderStatus.valueOf(entity.getStatus()), entity.getCurrencyCode(),
                items, entity.getPaidMinor(), entity.getRefundedMinor(), entity.getVersion());
    }

    private OrderItem toItem(OrderItemEntity entity) {
        return new OrderItem(entity.getOrderItemNo(), entity.getSkuId(), entity.getSkuCode(),
                entity.getName(), entity.getQuantity(), entity.getPriceMinor(), entity.getCurrencyCode());
    }

    private OrderEntity toEntity(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.setId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        entity.setUserId(order.getUserId());
        entity.setMerchantId(order.getMerchantId());
        entity.setPaymentNo(order.getPaymentNo());
        entity.setStatus(order.getStatus().name());
        entity.setCurrencyCode(order.getCurrencyCode());
        entity.setTotalMinor(order.getTotalMinor());
        entity.setPaidMinor(order.getPaidMinor());
        entity.setRefundedMinor(order.getRefundedMinor());
        entity.setVersion(order.getVersion());
        return entity;
    }

    private OrderItemEntity toItemEntity(String orderNo, OrderItem item) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.setOrderNo(orderNo);
        entity.setOrderItemNo(item.getOrderItemNo());
        entity.setSkuId(item.getSkuId());
        entity.setSkuCode(item.getSkuCode());
        entity.setName(item.getName());
        entity.setQuantity(item.getQuantity());
        entity.setPriceMinor(item.getPriceMinor());
        entity.setCurrencyCode(item.getCurrencyCode());
        return entity;
    }
}
