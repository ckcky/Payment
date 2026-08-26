package com.payment.entitlement.infra.persistence;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import com.payment.entitlement.domain.EntitlementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 权益持久化集成测试（H2，MySQL 兼容模式）：验证 PO↔领域映射、审计字段、乐观锁。
 */
@SpringBootTest
class EntitlementPersistenceTest {

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Test
    void entitlementRoundTrip() {
        Entitlement e = new Entitlement("user_1", "order_1", "ful_1", 3, "default", null);
        e.grant();
        entitlementRepository.save(e);

        Entitlement reloaded = entitlementRepository.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(e.getId());
        assertThat(reloaded.getUserId()).isEqualTo("user_1");
        assertThat(reloaded.getOrderId()).isEqualTo("order_1");
        assertThat(reloaded.getSourceFulfillmentId()).isEqualTo("ful_1");
        assertThat(reloaded.getStatus()).isEqualTo(EntitlementStatus.AVAILABLE);
        assertThat(reloaded.getAvailableQuantity()).isEqualTo(3);
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(entitlementRepository.findBySourceFulfillmentId("ful_1")).isPresent();
    }

    @Test
    void optimisticLockRejectsStaleUpdate() {
        Entitlement e = new Entitlement("user_1", "order_1", "ful_1", 3, "default", null);
        e.grant();
        entitlementRepository.save(e);

        Entitlement first = entitlementRepository.findById(e.getId()).orElseThrow();
        Entitlement second = entitlementRepository.findById(e.getId()).orElseThrow();

        first.consume(1);
        entitlementRepository.save(first);

        second.consume(1);
        assertThatThrownBy(() -> entitlementRepository.save(second))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }
}
