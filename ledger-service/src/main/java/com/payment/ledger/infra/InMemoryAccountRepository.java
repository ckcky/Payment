package com.payment.ledger.infra;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.domain.AccountDefinition;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存账户两级目录（仅单测装配用）：测试通过 {@link #seedDefinition} / {@link #seedInstance}
 * 搭建与 schema seed 对齐的最小科目表。
 */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, AccountDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, AccountInstance> instancesByKey = new ConcurrentHashMap<>();
    private final AtomicLong instanceIdGen = new AtomicLong(100);

    public void seedDefinition(AccountDefinition definition) {
        definitions.put(definition.getCode(), definition);
    }

    /** 预置实例（渠道 seed / 平台单例 / LEGACY 哨兵），返回带 id 的实例。 */
    public AccountInstance seedInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                        String ownerId, String currency) {
        return instancesByKey.computeIfAbsent(key(definitionCode, ownerType, ownerId, currency), k ->
                new AccountInstance(instanceIdGen.incrementAndGet(), definitionCode, ownerType,
                        ownerId, currency));
    }

    @Override
    public Optional<AccountDefinition> findDefinition(String code) {
        return Optional.ofNullable(definitions.get(code));
    }

    @Override
    public List<AccountDefinition> findAllDefinitions() {
        return List.copyOf(definitions.values());
    }

    @Override
    public Optional<AccountInstance> findInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                                  String ownerId, String currency) {
        return Optional.ofNullable(instancesByKey.get(key(definitionCode, ownerType, ownerId, currency)));
    }

    @Override
    public Optional<AccountInstance> findInstanceById(long id) {
        return instancesByKey.values().stream().filter(i -> i.getId() != null && i.getId() == id).findFirst();
    }

    @Override
    public List<AccountInstance> findAllInstances() {
        return instancesByKey.values().stream()
                .sorted(java.util.Comparator.comparingLong(AccountInstance::getId))
                .toList();
    }

    @Override
    public AccountInstance openInstance(String definitionCode, AccountCode.OwnerDimension ownerType,
                                        String ownerId, String currency) {
        return seedInstance(definitionCode, ownerType, ownerId, currency);
    }

    private static String key(String definitionCode, AccountCode.OwnerDimension ownerType,
                              String ownerId, String currency) {
        return definitionCode + "|" + ownerType + "|" + ownerId + "|" + currency;
    }
}
