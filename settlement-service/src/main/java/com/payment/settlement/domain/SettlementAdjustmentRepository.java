package com.payment.settlement.domain;

import java.util.List;
import java.util.Optional;

/**
 * 结算调整项仓储边界（领域接口，不依赖持久化实现）。
 */
public interface SettlementAdjustmentRepository {

    Optional<SettlementAdjustment> findByIdempotencyKey(String idempotencyKey);

    /** 某商户某周期下的 ACTIVE 调整项（参与净额计算）。 */
    List<SettlementAdjustment> findActiveByMerchantAndPeriod(String merchantId, String period);

    SettlementAdjustment save(SettlementAdjustment adjustment);
}
