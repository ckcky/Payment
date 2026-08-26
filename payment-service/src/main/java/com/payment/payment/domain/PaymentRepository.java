package com.payment.payment.domain;

import java.util.Optional;

/**
 * 支付仓储边界（领域接口，不依赖持久化实现）。
 */
public interface PaymentRepository {

    Optional<Payment> findById(Long id);

    Optional<Payment> findByTransactionId(String transactionId);

    Payment save(Payment payment);
}
