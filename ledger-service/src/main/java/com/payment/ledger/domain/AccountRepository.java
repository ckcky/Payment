package com.payment.ledger.domain;

import com.payment.common.dto.rpc.AccountCode;

import java.util.List;
import java.util.Optional;

/**
 * 账户仓储边界（spec 031 §5，ADR-0078）：科目定义（Definition）与账户实例（Instance）
 * 两级目录的领域端口。Definition 是类型目录；可记账的是 Instance。
 */
public interface AccountRepository {

    Optional<AccountDefinition> findDefinition(String code);

    List<AccountDefinition> findAllDefinitions();

    /** 按唯一键 {@code (definitionCode, ownerType, ownerId, currency)} 查实例。 */
    Optional<AccountInstance> findInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                          String ownerId, String currency);

    Optional<AccountInstance> findInstanceById(long id);

    List<AccountInstance> findAllInstances();

    /** 幂等开户（insert-if-absent，spec §5.4）：已存在即返回既有实例，并发下回查兜底。 */
    AccountInstance openInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                 String ownerId, String currency);
}
