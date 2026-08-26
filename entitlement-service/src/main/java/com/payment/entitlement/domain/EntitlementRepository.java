package com.payment.entitlement.domain;

import java.util.Optional;

/**
 * 权益仓储接口（依赖倒置：domain 声明，infra 实现）。
 */
public interface EntitlementRepository {

    Optional<Entitlement> findById(Long id);

    Optional<Entitlement> findBySourceFulfillmentId(String sourceFulfillmentId);

    Entitlement save(Entitlement entitlement);
}
