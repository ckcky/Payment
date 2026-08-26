package com.payment.settlement.domain;

import java.util.Optional;

/**
 * 结算仓储边界（领域接口，不依赖持久化实现）。
 */
public interface SettlementRepository {

    Optional<SettlementBatch> findById(Long id);

    Optional<SettlementBatch> findByMerchantAndPeriod(String merchantId, String period);

    SettlementBatch save(SettlementBatch batch);
}
