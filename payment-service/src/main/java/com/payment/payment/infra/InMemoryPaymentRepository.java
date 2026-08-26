package com.payment.payment.infra;

import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * 内存支付仓储（MVP）。生产实现替换为 MyBatis-Plus 实体 + 数据库唯一约束兜底幂等。
 */
@Repository
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
    public Payment save(Payment payment) {
        if (payment.getId() == null) {
            payment.setId(idGen.incrementAndGet());
        }
        byId.put(payment.getId(), payment);
        return payment;
    }
}
