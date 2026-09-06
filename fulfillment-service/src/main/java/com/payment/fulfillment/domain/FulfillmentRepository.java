package com.payment.fulfillment.domain;

import java.util.List;
import java.util.Optional;

/**
 * 履约仓储端口（领域层接口，不依赖 MyBatis / Spring；实现见 infra 层）。
 *
 * <p>spec 018 / ADR-0066：履约按订单明细（order_item）粒度创建，幂等键细化为
 * {@code (sourcePaymentNo, orderItemId)}；同一订单可有多条履约（每明细一条）。</p>
 */
public interface FulfillmentRepository {

    Optional<Fulfillment> findById(Long id);

    /** 明细粒度幂等查询：同一支付成功事件 + 同一订单明细只创建一条履约（spec 018 AC3.3）。 */
    Optional<Fulfillment> findBySourcePaymentNoAndOrderItemId(String sourcePaymentNo, String orderItemId);

    /** 按订单查询全部履约（spec 018：一单多明细 = 多条履约），用于退款时逐条撤销。 */
    List<Fulfillment> findByOrderNo(String orderNo);

    Fulfillment save(Fulfillment fulfillment);
}
