package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * accounts（账户实例表）Mapper：唯一键 {@code uk_instance} 承接幂等开户的撞键回查。
 */
public interface AccountInstanceMapper extends BaseMapper<AccountInstanceEntity> {
}
