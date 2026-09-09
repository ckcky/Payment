package com.payment.common.core.idempotency;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内幂等登记实现，用于基础测试与无持久化场景。
 *
 * <p>生产/集成场景必须替换为基于数据库唯一约束的实现（见各服务 infra 层）。</p>
 *
 * <p><b>当前使用状态（spec 023 T18）</b>：仅由 {@code CommonCoreAutoConfiguration} 注册为
 * 默认 Bean + common-core 自身单测引用；各业务服务的支付/退款/下单入口幂等均走
 * DB 唯一键 + 状态机（不注入此实现），故业务主链路零引用——保留它作为 SPI 的
 * 开箱默认值与测试基座，非可删死代码。</p>
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
