package com.payment.common.core.idempotency;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内幂等登记实现，用于基础测试与无持久化场景。
 *
 * <p>生产/集成场景必须替换为基于数据库唯一约束的实现（见各服务 infra 层）。</p>
 */
public final class InMemoryIdempotencyRegistry implements IdempotencyRegistry {

    private final Map<IdempotencyKey, String> results = new ConcurrentHashMap<>();

    @Override
    public boolean recordIfAbsent(IdempotencyKey key, String result) {
        return results.putIfAbsent(key, result) == null;
    }

    @Override
    public Optional<String> find(IdempotencyKey key) {
        return Optional.ofNullable(results.get(key));
    }
}
