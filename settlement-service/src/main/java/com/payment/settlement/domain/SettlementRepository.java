package com.payment.settlement.domain;

import java.util.Optional;

/**
 * 结算仓储边界（领域接口，不依赖持久化实现）。
 */
public interface SettlementRepository {

    Optional<SettlementBatch> findById(Long id);

    Optional<SettlementBatch> findByMerchantAndPeriod(String merchantId, String period);

    /** 按幂等键查询已受理批次（幂等回放，Constitution §4.1 数据库唯一约束兜底）。 */
    Optional<SettlementBatch> findByIdempotencyKey(String idempotencyKey);

    SettlementBatch save(SettlementBatch batch);
}
