package com.payment.order.infra.persistence.trace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.order.domain.OrderEvent;
import com.payment.order.domain.OrderEventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 订单事件轨迹仓储 MyBatis 实现（spec 029 / FR-401、FR-403）。
 *
 * <p>幂等：{@code uk_order_event_log_msg_id} 唯一约束兜底——重复投递（同 msgId）
 * 撞唯一键被 {@link DuplicateKeyException} 吸收，不产生第二行（INV-2 / SC-6）。</p>
 */
@Repository
public class MybatisOrderEventLogRepository implements OrderEventLogRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisOrderEventLogRepository.class);

    private final OrderEventLogMapper mapper;

    public MybatisOrderEventLogRepository(OrderEventLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean append(OrderEvent event) {
        OrderEventLogEntity entity = toEntity(event);
        try {
            mapper.insert(entity);
            return true;
        } catch (DuplicateKeyException ex) {
            log.debug("轨迹重复投递吸收 msgId={} orderNo={}", event.msgId(), event.orderNo());
            return false;
        }
    }

    @Override
    public List<OrderEvent> findByOrderNo(String orderNo) {
        return mapper.selectList(Wrappers.<OrderEventLogEntity>lambdaQuery()
                        .eq(OrderEventLogEntity::getOrderNo, orderNo)
                        .orderByAsc(OrderEventLogEntity::getOccurredAt)
                        .orderByAsc(OrderEventLogEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private OrderEventLogEntity toEntity(OrderEvent e) {
        OrderEventLogEntity entity = new OrderEventLogEntity();
        entity.setOrderNo(e.orderNo());
        entity.setEventType(e.eventType());
        entity.setTopic(e.topic());
        entity.setMsgId(e.msgId());
        entity.setTraceId(e.traceId());
        entity.setProducer(e.producer());
        entity.setPayloadJson(e.payloadJson());
        entity.setOccurredAt(e.occurredAt() == null ? LocalDateTime.now(ZoneOffset.UTC)
                : LocalDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC));
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return entity;
    }

    private OrderEvent toDomain(OrderEventLogEntity entity) {
        Instant occurred = entity.getOccurredAt() == null
                ? null : entity.getOccurredAt().toInstant(ZoneOffset.UTC);
        return new OrderEvent(entity.getOrderNo(), entity.getEventType(), entity.getTopic(),
                entity.getMsgId(), entity.getTraceId(), entity.getProducer(),
                entity.getPayloadJson(), occurred);
    }
}
