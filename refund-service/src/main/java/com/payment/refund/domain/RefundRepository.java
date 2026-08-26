package com.payment.refund.domain;

import java.util.List;
import java.util.Optional;

/**
 * 退款仓储边界（领域接口，不依赖持久化实现）。
 */
public interface RefundRepository {

    Optional<Refund> findById(Long id);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    /** 同一支付下的全部退款（用于累计退款金额校验）。 */
    List<Refund> findByPaymentId(String paymentId);

    List<Refund> findByOrderId(String orderId);

    Refund save(Refund refund);
}
