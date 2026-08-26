package com.payment.order.infra;

import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存交易仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisTransactionRepository} 承接。
 */
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<Long, Transaction> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<Transaction> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Transaction> findByOrderId(String orderId) {
        return byId.values().stream()
                .filter(t -> orderId.equals(t.getOrderId()))
                .findFirst();
    }

    @Override
    public Transaction save(Transaction transaction) {
        if (transaction.getId() == null) {
            transaction.setId(idGen.incrementAndGet());
        }
        byId.put(transaction.getId(), transaction);
        return transaction;
    }
}
