package com.payment.payment.domain;

import java.util.List;
import java.util.Optional;

/**
 * 支付仓储边界（领域接口，不依赖持久化实现）。
 */
public interface PaymentRepository {

    Optional<Payment> findById(Long id);
    /** 按业务单号查询（对外接口 / 跨服务引用一律用 paymentNo，ADR-0063）。 */
    Optional<Payment> findByPaymentNo(String paymentNo);

    Optional<Payment> findByTransactionId(String transactionId);

    /**
     * 按订单号查询本订单下的支付单（spec 029 / FR-301 / T49：消费 order.cancelled 时关闭）。
     * 一订单可能有多张支付单（Feature 015 一交易多支付单），故返回列表。
     */
    List<Payment> findByOrderNo(String orderNo);

    /** 按幂等键查询已受理支付（幂等回放，Constitution §4.1 数据库唯一约束兜底）。 */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** 按平台状态查询支付（对账事实抽取用）。 */
    List<Payment> findByStatus(PaymentStatus status);

    /** 统计同一交易下的支付单数量（一交易多支付单时计算 attemptSeq 用，Feature 015）。 */
    long countByTransactionId(String transactionId);

    Payment save(Payment payment);
}
