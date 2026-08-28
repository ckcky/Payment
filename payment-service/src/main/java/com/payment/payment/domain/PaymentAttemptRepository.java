package com.payment.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 支付尝试仓储边界（领域接口）。
 */
public interface PaymentAttemptRepository {

    Optional<PaymentAttempt> findById(Long id);

    List<PaymentAttempt> findByPaymentId(Long paymentId);

    /** 查询已到退避时刻、待重试的尝试（spec US3 / ADR-0013）。 */
    List<PaymentAttempt> findRetryableDue(Instant now);

    PaymentAttempt save(PaymentAttempt attempt);
}
