package com.payment.fulfillment.infra;

import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版履约仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisFulfillmentRepository} 承接。
 * 线程安全，用 {@code (sourcePaymentNo, orderItemId)} 维护明细粒度幂等索引（spec 018）。
 */
public class InMemoryFulfillmentRepository implements FulfillmentRepository {

    /** 明细粒度幂等键：sourcePaymentNo + 分隔符 + orderItemId（orderItemId 为 OI 业务单号，不含该分隔符）。 */
    private static String idemKey(String sourcePaymentNo, String orderItemId) {
        return sourcePaymentNo + "::" + orderItemId;
    }

    private final ConcurrentHashMap<Long, Fulfillment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bySourcePaymentItem = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Optional<Fulfillment> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Fulfillment> findBySourcePaymentNoAndOrderItemId(String sourcePaymentNo, String orderItemId) {
        Long id = bySourcePaymentItem.get(idemKey(sourcePaymentNo, orderItemId));
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Fulfillment> findByOrderNo(String orderNo) {
        return byId.values().stream()
                .filter(f -> orderNo.equals(f.getOrderNo()))
                .toList();
    }

    @Override
    public Fulfillment save(Fulfillment fulfillment) {
        if (fulfillment.getId() == null) {
            fulfillment.setId(idGenerator.incrementAndGet());
        }
        byId.put(fulfillment.getId(), fulfillment);
        bySourcePaymentItem.put(idemKey(fulfillment.getSourcePaymentNo(), fulfillment.getOrderItemId()),
                fulfillment.getId());
        return fulfillment;
    }
}
