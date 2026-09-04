package com.payment.payment.domain;

import java.util.List;
import java.util.Optional;

/**
 * 支付尝试仓储边界（领域接口）。
 *
 * <p>ADR-0013 修订后重试在请求内联完成，<b>仓储不再承担重试调度</b>：没有「待重试队列」查询，
 * 也没有 {@code next_retry_at} 落库字段。</p>
 */
public interface PaymentAttemptRepository {

    Optional<PaymentAttempt> findById(Long id);

    List<PaymentAttempt> findByPaymentNo(String paymentNo);

    PaymentAttempt save(PaymentAttempt attempt);
}
