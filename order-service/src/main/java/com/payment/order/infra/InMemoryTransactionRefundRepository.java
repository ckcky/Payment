package com.payment.order.infra;

import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.TransactionRefundRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存交易层退款单仓储：仅用于领域/编排单测（不走 Spring 注入），
 * 生产由 {@code MybatisTransactionRefundRepository} 承接。
 */
public class InMemoryTransactionRefundRepository implements TransactionRefundRepository {

    private final Map<Long, RefundOrder> byId = new ConcurrentHashMap<>();
    private final Map<String, RefundOrder> byIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public RefundOrder save(RefundOrder refundOrder) {
        if (refundOrder.getId() == null) {
            refundOrder.setId(idGen.incrementAndGet());
        }
        // spec 034 / T13：内存实现的 updated_at 语义对齐 DB 列（首次落库起即有值；
        // 已有值不覆盖——TT-7 以 rehydrate 注入回拨时间戳，保存不得洗掉）。
        if (refundOrder.getUpdatedAt() == null) {
            refundOrder.markPersistedAt(java.time.Instant.now());
        }
        byId.put(refundOrder.getId(), refundOrder);
        byIdempotencyKey.put(refundOrder.getIdempotencyKey(), refundOrder);
        return refundOrder;
    }

    @Override
    public Optional<RefundOrder> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RefundOrder> findByRefundNo(String refundNo) {
        return byId.values().stream()
                .filter(r -> r.getRefundNo().equals(refundNo))
                .findFirst();
    }

    @Override
    public Optional<RefundOrder> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public List<RefundOrder> findByTransactionNo(String transactionNo) {
        return byId.values().stream()
                .filter(r -> r.getTransactionNo().equals(transactionNo))
                .toList();
    }

    @Override
    public List<RefundOrder> findByOrderNo(String orderNo) {
        return byId.values().stream()
                .filter(r -> r.getOrderNo().equals(orderNo))
                .toList();
    }

    @Override
    public List<RefundOrder> findByStatus(com.payment.order.domain.RefundOrderStatus status) {
        return byId.values().stream()
                .filter(r -> r.getStatus() == status)
                .sorted(java.util.Comparator.comparing(RefundOrder::getId))
                .toList();
    }
}
