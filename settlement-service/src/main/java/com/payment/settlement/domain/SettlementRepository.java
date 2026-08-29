package com.payment.settlement.domain;

import java.util.List;
import java.util.Optional;

/**
 * 结算仓储边界（领域接口，不依赖持久化实现）。
 */
public interface SettlementRepository {

    Optional<SettlementBatch> findById(Long id);

    Optional<SettlementBatch> findByMerchantAndPeriod(String merchantId, String period);

    /** 按幂等键查询已受理批次（幂等回放，Constitution §4.1 数据库唯一约束兜底）。 */
    Optional<SettlementBatch> findByIdempotencyKey(String idempotencyKey);

    /** 按可选商户/周期条件列出批次（GET /batches 端点，ADR-0023）。 */
    List<SettlementBatch> listBatches(String merchantId, String period);

    SettlementBatch save(SettlementBatch batch);
}
