package com.payment.payment.infra;

import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存支付仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisPaymentRepository} 承接。
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<Long, Payment> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<Payment> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Payment> findByTransactionId(String transactionId) {
        return byId.values().stream()
                .filter(p -> transactionId.equals(p.getTransactionId()))
                .findFirst();
    }

    @Override
    public List<Payment> findByStatus(PaymentStatus status) {
        return byId.values().stream()
                .filter(p -> status == p.getStatus())
                .toList();
    }

    @Override
    public Payment save(Payment payment) {
        if (payment.getId() == null) {
            payment.setId(idGen.incrementAndGet());
        }
        byId.put(payment.getId(), payment);
        return payment;
    }
}
