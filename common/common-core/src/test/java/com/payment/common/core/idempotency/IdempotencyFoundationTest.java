package com.payment.common.core.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等基础（T018）：同一幂等键重复处理返回首次结果；不同作用域/键互不影响。
 */
class IdempotencyFoundationTest {

    private final IdempotencyRegistry registry = new InMemoryIdempotencyRegistry();

    @Test
    void firstRecordWins() {
        IdempotencyKey key = IdempotencyKey.of("payment", "req-1");
        assertThat(registry.recordIfAbsent(key, "pay_1")).isTrue();
        assertThat(registry.recordIfAbsent(key, "pay_2")).isFalse();
        assertThat(registry.find(key)).contains("pay_1");
    }

    @Test
    void differentKeysIndependent() {
        IdempotencyKey a = IdempotencyKey.of("payment", "req-1");
        IdempotencyKey b = IdempotencyKey.of("payment", "req-2");
        registry.recordIfAbsent(a, "pay_1");
        registry.recordIfAbsent(b, "pay_2");
        assertThat(registry.find(a)).contains("pay_1");
        assertThat(registry.find(b)).contains("pay_2");
    }

    @Test
    void differentScopesIndependent() {
        IdempotencyKey a = IdempotencyKey.of("payment", "req-1");
        IdempotencyKey b = IdempotencyKey.of("refund", "req-1");
        registry.recordIfAbsent(a, "pay_1");
        registry.recordIfAbsent(b, "ref_1");
        assertThat(registry.find(a)).contains("pay_1");
        assertThat(registry.find(b)).contains("ref_1");
    }

    @Test
    void absentReturnsEmpty() {
        assertThat(registry.find(IdempotencyKey.of("payment", "nope"))).isEmpty();
    }

    @Test
    void blankKeyRejected() {
        assertThatThrownBy(() -> IdempotencyKey.of("payment", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
