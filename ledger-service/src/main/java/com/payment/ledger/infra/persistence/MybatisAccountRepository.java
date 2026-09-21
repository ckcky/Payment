package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.domain.AccountDefinition;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 账户两级模型的 MyBatis 实现（spec 031 §5）。
 *
 * <p>幂等开户（§5.4）：先查后插，撞 {@code uk_instance} 回查返回既有实例——
 * 与记账幂等同一套「先查后插 + DuplicateKey 回查」模式。</p>
 */
@Repository
public class MybatisAccountRepository implements AccountRepository {

    private final AccountDefinitionMapper definitionMapper;
    private final AccountInstanceMapper instanceMapper;

    public MybatisAccountRepository(AccountDefinitionMapper definitionMapper,
                                    AccountInstanceMapper instanceMapper) {
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
    }

    @Override
    public Optional<AccountDefinition> findDefinition(String code) {
        AccountDefinitionEntity entity = definitionMapper.selectOne(
                Wrappers.<AccountDefinitionEntity>lambdaQuery().eq(AccountDefinitionEntity::getCode, code));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<AccountDefinition> findAllDefinitions() {
        return definitionMapper.selectList(null).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<AccountInstance> findInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                                  String ownerId, String currency) {
        AccountInstanceEntity entity = instanceMapper.selectOne(
                Wrappers.<AccountInstanceEntity>lambdaQuery()
                        .eq(AccountInstanceEntity::getDefinitionCode, definitionCode)
                        .eq(AccountInstanceEntity::getOwnerType, ownerType.name())
                        .eq(AccountInstanceEntity::getOwnerId, ownerId)
                        .eq(AccountInstanceEntity::getCurrency, currency));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<AccountInstance> findInstanceById(long id) {
        return Optional.ofNullable(instanceMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public List<AccountInstance> findAllInstances() {
        return instanceMapper.selectList(
                        Wrappers.<AccountInstanceEntity>lambdaQuery().orderByAsc(AccountInstanceEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public AccountInstance openInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                        String ownerId, String currency) {
        Optional<AccountInstance> existing = findInstance(definitionCode, ownerType, ownerId, currency);
        if (existing.isPresent()) {
            return existing.get();
        }
        AccountInstanceEntity entity = new AccountInstanceEntity();
        entity.setDefinitionCode(definitionCode);
        entity.setOwnerType(ownerType.name());
        entity.setOwnerId(ownerId);
        entity.setCurrency(currency);
        entity.setCode(definitionCode);
        entity.setName(definitionName(definitionCode) + "-" + ownerId);
        entity.setType(definitionType(definitionCode));
        entity.setCreatedAt(Instant.now());
        try {
            instanceMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            return findInstance(definitionCode, ownerType, ownerId, currency)
                    .orElseThrow(() -> new IllegalStateException(
                            "account instance duplicate but not found: " + definitionCode + ":" + ownerId));
        }
        return toDomain(entity);
    }

    private String definitionName(String code) {
        AccountDefinitionEntity definition = definitionMapper.selectOne(
                Wrappers.<AccountDefinitionEntity>lambdaQuery().eq(AccountDefinitionEntity::getCode, code));
        return definition == null ? code : definition.getName();
    }

    private String definitionType(String code) {
        AccountDefinitionEntity definition = definitionMapper.selectOne(
                Wrappers.<AccountDefinitionEntity>lambdaQuery().eq(AccountDefinitionEntity::getCode, code));
        return definition == null ? "ASSET" : definition.getType();
    }

    private AccountDefinition toDomain(AccountDefinitionEntity entity) {
        return new AccountDefinition(entity.getCode(), entity.getName(),
                AccountDefinition.AccountType.valueOf(entity.getType()),
                AccountDefinition.NormalSide.valueOf(entity.getNormalBalance()),
                AccountCode.OwnerDimension.valueOf(entity.getOwnerDimension()),
                AccountCode.Status.valueOf(entity.getStatus()));
    }

    private AccountInstance toDomain(AccountInstanceEntity entity) {
        return new AccountInstance(entity.getId(), entity.getDefinitionCode(),
                AccountCode.OwnerDimension.valueOf(entity.getOwnerType()), entity.getOwnerId(),
                entity.getCurrency());
    }
}
