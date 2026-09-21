package com.payment.order.domain;

import java.util.List;
import java.util.Optional;

/**
 * 交易层退款单仓储边界（领域接口，spec 019 / ADR-0067）。
 */
public interface TransactionRefundRepository {

    RefundOrder save(RefundOrder refundOrder);

    Optional<RefundOrder> findById(Long id);

    Optional<RefundOrder> findByRefundNo(String refundNo);

    /** 幂等寻址：幂等键 = TXRF（uk_transaction_refunds_idempotency_key）。 */
    Optional<RefundOrder> findByIdempotencyKey(String idempotencyKey);

    List<RefundOrder> findByTransactionNo(String transactionNo);

    List<RefundOrder> findByOrderNo(String orderNo);

    /**
     * 按状态查询（spec 034 / T13：搁浅退款扫描器用）。实现按 id 升序返回（确定性扫描顺序）。
     */
    List<RefundOrder> findByStatus(RefundOrderStatus status);
}
