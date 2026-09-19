package com.payment.order.infra.persistence.trace;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 订单事件轨迹表 Mapper（MyBatis-Plus BaseMapper，spec 029 / FR-401）。
 */
public interface OrderEventLogMapper extends BaseMapper<OrderEventLogEntity> {
}
