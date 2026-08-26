package com.payment.common.core.idempotency;

import java.util.Objects;

/**
 * 幂等键：业务作用域 + 调用方提供的键。
 *
 * <p>资金入口（支付/退款/结算）MUST 具备幂等键（Constitution §4.1 / §2.4）。
 * 相同幂等键的重复请求 MUST NOT 产生重复资金动作。</p>
 */
public final class IdempotencyKey {

    private final String scope;
    private final String key;

    private IdempotencyKey(String scope, String key) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.key = Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public static IdempotencyKey of(String scope, String key) {
        return new IdempotencyKey(scope, key);
    }

    public String getScope() {
        return scope;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyKey other)) {
            return false;
        }
        return scope.equals(other.scope) && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, key);
    }

    @Override
    public String toString() {
        return scope + ':' + key;
    }
}
