package com.payment.refund.infra;

import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存退款仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 MyBatis 实现承接。
 */
public class InMemoryRefundRepository implements RefundRepository {

    private final Map<Long, Refund> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<Refund> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
        return byId.values().stream()
                .filter(r -> idempotencyKey.equals(r.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public List<Refund> findByPaymentId(Long paymentId) {
        return byId.values().stream()
                .filter(r -> paymentId.equals(r.getPaymentId()))
                .toList();
    }

    @Override
    public List<Refund> findByOrderId(String orderId) {
        return byId.values().stream()
                .filter(r -> orderId.equals(r.getOrderId()))
                .toList();
    }

    @Override
    public List<Refund> findByStatus(RefundStatus status) {
        return byId.values().stream()
                .filter(r -> status == r.getStatus())
                .toList();
    }

    @Override
    public void lockForIntake(Long paymentId) {
        // 内存实现无并发串行化需求（单测为单线程），生产由 MyBatis 排他锁承接。
    }

    @Override
    public Refund save(Refund refund) {
        if (refund.getId() == null) {
            refund.setId(idGen.incrementAndGet());
        }
        byId.put(refund.getId(), refund);
        return refund;
    }
}
