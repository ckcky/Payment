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
    List<Refund> findByPaymentId(Long paymentId);

    List<Refund> findByOrderId(String orderId);

    /** 按退款状态查询（对账事实抽取用）。 */
    List<Refund> findByStatus(RefundStatus status);

    /** 持有 {@code paymentId} 的退款受理排他锁（H1：串行化累计退款金额读改写）。 */
    void lockForIntake(Long paymentId);

    Refund save(Refund refund);
}
