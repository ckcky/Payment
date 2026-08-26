package com.payment.entitlement.domain;

import java.util.List;
import java.util.Optional;

/**
 * 权益仓储接口（依赖倒置：domain 声明，infra 实现）。
 */
public interface EntitlementRepository {

    Optional<Entitlement> findById(Long id);

    Optional<Entitlement> findBySourceFulfillmentId(String sourceFulfillmentId);

    /** 返回某订单授予的全部权益（退款后处理按订单撤销权益时使用）。 */
    List<Entitlement> findByOrderId(String orderId);

    Entitlement save(Entitlement entitlement);
}
