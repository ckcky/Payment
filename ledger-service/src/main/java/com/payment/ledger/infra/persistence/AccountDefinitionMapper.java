package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * account_definitions Mapper（seed 只读目录；新增科目走 ADR + schema 变更）。
 */
public interface AccountDefinitionMapper extends BaseMapper<AccountDefinitionEntity> {
}
