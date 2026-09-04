package com.payment.payment.infra;

import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存支付尝试仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisPaymentAttemptRepository} 承接。
 */
public class InMemoryPaymentAttemptRepository implements PaymentAttemptRepository {

    private final Map<Long, PaymentAttempt> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<PaymentAttempt> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<PaymentAttempt> findByPaymentNo(String paymentNo) {
        return byId.values().stream()
                .filter(a -> a.getPaymentNo().equals(paymentNo))
                .toList();
    }

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        if (attempt.getId() == null) {
            attempt.setId(idGen.incrementAndGet());
        }
        byId.put(attempt.getId(), attempt);
        return attempt;
    }
}
