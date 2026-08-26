package com.payment.payment.domain;

import java.util.List;
import java.util.Optional;

/**
 * 支付仓储边界（领域接口，不依赖持久化实现）。
 */
public interface PaymentRepository {

    Optional<Payment> findById(Long id);

    Optional<Payment> findByTransactionId(String transactionId);

    /** 按幂等键查询已受理支付（幂等回放，Constitution §4.1 数据库唯一约束兜底）。 */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** 按平台状态查询支付（对账事实抽取用）。 */
    List<Payment> findByStatus(PaymentStatus status);

    Payment save(Payment payment);
}
