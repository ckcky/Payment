package com.payment.fulfillment.domain;

import java.util.Optional;

/**
 * 履约仓储端口（领域层接口，不依赖 MyBatis / Spring；实现见 infra 层）。
 */
public interface FulfillmentRepository {

    Optional<Fulfillment> findById(Long id);

    /** 按支付幂等键查询，用于保证同一支付成功事件只创建一条履约。 */
    Optional<Fulfillment> findBySourcePaymentNo(String sourcePaymentNo);

    /** 按订单查询，用于退款时定位需撤销的履约。 */
    Optional<Fulfillment> findByOrderNo(String orderNo);

    Fulfillment save(Fulfillment fulfillment);
}
