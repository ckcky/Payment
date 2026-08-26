package com.payment.payment.domain;

import java.util.List;
import java.util.Optional;

/**
 * 支付尝试仓储边界（领域接口）。
 */
public interface PaymentAttemptRepository {

    Optional<PaymentAttempt> findById(Long id);

    List<PaymentAttempt> findByPaymentId(Long paymentId);

    PaymentAttempt save(PaymentAttempt attempt);
}
