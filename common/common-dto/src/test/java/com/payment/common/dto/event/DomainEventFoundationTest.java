package com.payment.common.dto.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件契约基础（T019）：跨服务事件必须携带必需元数据，且契约只暴露可序列化的原始字段，
 * 不暴露产生方模块的内部实体。
 */
class DomainEventFoundationTest {

    @Test
    void paymentSucceededCarriesRequiredMetadata() {
        PaymentSucceeded e = new PaymentSucceeded("payment", "pay_1", 1L, "order_1", "txn_1", "user_1", 1250L, "USD");
        assertThat(e.getEventId()).isNotBlank();
        assertThat(e.getOccurredAt()).isNotNull();
        assertThat(e.getSourceModule()).isEqualTo("payment");
        assertThat(e.getAggregateId()).isEqualTo("pay_1");
        assertThat(e.getVersion()).isEqualTo(1L);
        assertThat(e.getEventType()).isEqualTo("PaymentSucceeded");
    }

    @Test
    void eachEventHasUniqueId() {
        PaymentSucceeded a = new PaymentSucceeded("payment", "pay_1", 1L, "o1", "t1", "u1", 1L, "USD");
        PaymentSucceeded b = new PaymentSucceeded("payment", "pay_2", 1L, "o2", "t2", "u2", 1L, "USD");
        assertThat(a.getEventId()).isNotEqualTo(b.getEventId());
    }

    @Test
    void eventContractExposesOnlySerializableFields() {
        // getter 表面只允许 String/基本类型/包装类/Instant/枚举，杜绝泄漏内部实体。
        for (Method m : contractGetters(PaymentSucceeded.class)) {
            assertThat(isContractType(m.getReturnType()))
                    .as("getter %s returns non-contract type %s", m.getName(), m.getReturnType())
                    .isTrue();
        }
    }

    private static List<Method> contractGetters(Class<?> type) {
        List<Method> result = new ArrayList<>();
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().startsWith("get") && m.getParameterCount() == 0) {
                    result.add(m);
                }
            }
            c = c.getSuperclass();
        }
        return result;
    }

    private static boolean isContractType(Class<?> t) {
        return t == String.class
                || t == long.class || t == int.class || t == boolean.class
                || t == Long.class || t == Integer.class || t == Boolean.class
                || t == Instant.class
                || t.isEnum();
    }
}
