package com.payment.order.domain;

import java.util.List;

/**
 * 订单事件轨迹仓储端口（只读投影，spec 029 / FR-401、FR-403）。
 *
 * <p>INV-5：轨迹为只读投影，写入口只在 trace 消费组；业务链路 MUST NOT 依赖本仓储。</p>
 */
public interface OrderEventLogRepository {

    /**
     * 落一条轨迹（幂等：msgId 唯一键，重复投递撞唯一约束被吸收）。
     *
     * @return 是否为新插入（false = 重复 msgId 已存在）
     */
    boolean append(OrderEvent event);

    /** 按订单号查询轨迹，时间升序（FR-403）。 */
    List<OrderEvent> findByOrderNo(String orderNo);
}
