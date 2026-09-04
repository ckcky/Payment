package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 退款后处理尝试 Mapper（ADR-0017）：仅提供基础 CRUD，复杂查询由仓储组装。
 */
@Mapper
public interface RefundPostProcessAttemptMapper extends BaseMapper<RefundPostProcessAttemptEntity> {
}
