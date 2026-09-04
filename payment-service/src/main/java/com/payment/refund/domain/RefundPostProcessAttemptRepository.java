package com.payment.refund.domain;

import java.util.List;

/**
 * 退款后处理尝试仓储边界（领域接口，不依赖持久化实现）。ADR-0017：追加式记录，终态不可变。
 */
public interface RefundPostProcessAttemptRepository {

    /** 落一条后处理尝试记录（履约/权益/记账各一次调用一条）。 */
    void save(RefundPostProcessAttempt attempt);

    /** 按退款 ID 查询全部后处理尝试，便于运营追踪与重放。 */
    List<RefundPostProcessAttempt> findByRefundId(Long refundId);
}
