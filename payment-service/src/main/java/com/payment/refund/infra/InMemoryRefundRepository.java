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
    public Optional<Refund> findByRefundNo(String refundNo) {
        return byId.values().stream()
                .filter(r -> refundNo.equals(r.getRefundNo()))
                .findFirst();
    }

    @Override
    public List<Refund> findByPaymentNo(String paymentNo) {
        return byId.values().stream()
                .filter(r -> paymentNo.equals(r.getPaymentNo()))
                .toList();
    }

    @Override
    public List<Refund> findByOrderNo(String orderNo) {
        return byId.values().stream()
                .filter(r -> orderNo.equals(r.getOrderNo()))
                .toList();
    }

    @Override
    public List<Refund> findByStatus(RefundStatus status) {
        return byId.values().stream()
                .filter(r -> status == r.getStatus())
                .toList();
    }

    /**
     * spec 032 / H-032-1 期间过滤：内存实现不建模 createdAt（测试用仓储），
     * 退化为仅按状态过滤——期间过滤的真实语义由 Mybatis/H2 集成测试承接。
     */
    @Override
    public List<Refund> findByStatusAndCreatedAtBetween(RefundStatus status,
                                                        java.time.LocalDateTime startInclusive,
                                                        java.time.LocalDateTime endExclusive) {
        return findByStatus(status);
    }

    @Override
    public void lockForIntake(String paymentNo) {
        // 内存实现无并发串行化需求（单测为单线程），生产由 MyBatis 排他锁承接。
    }

    @Override
    public Refund save(Refund refund) {
        if (refund.getId() == null) {
            refund.setId(idGen.incrementAndGet());
        }
        // spec 034 / T12：内存实现的 updated_at 语义对齐 DB 列（DB 为 ON UPDATE 维护，
        // 首次落库起即有值；已有值不覆盖——幂等重放保留原始时间戳，UNKNOWN 年龄不失真）。
        if (refund.getUpdatedAt() == null) {
            refund.markPersistedAt(java.time.Instant.now());
        }
        byId.put(refund.getId(), refund);
        return refund;
    }
}
