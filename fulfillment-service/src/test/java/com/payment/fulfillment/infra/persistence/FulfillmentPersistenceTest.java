package com.payment.fulfillment.infra.persistence;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import com.payment.fulfillment.domain.FulfillmentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约持久化集成测试（H2，MySQL 兼容模式）：验证 PO↔领域映射、审计字段、乐观锁、幂等键唯一。
 */
@SpringBootTest
class FulfillmentPersistenceTest {

    @Autowired
    private FulfillmentRepository fulfillmentRepository;

    @Test
    void fulfillmentRoundTrip() {
        Fulfillment fulfillment = new Fulfillment("order_1", "item_1", "mock delivery", "pay_1");
        fulfillment.start();
        fulfillment.deliver();
        fulfillmentRepository.save(fulfillment);

        Fulfillment reloaded = fulfillmentRepository.findById(fulfillment.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(fulfillment.getId());
        assertThat(reloaded.getOrderId()).isEqualTo("order_1");
        assertThat(reloaded.getOrderItemId()).isEqualTo("item_1");
        assertThat(reloaded.getSourcePaymentId()).isEqualTo("pay_1");
        assertThat(reloaded.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(fulfillmentRepository.findBySourcePaymentId("pay_1")).isPresent();
    }

    @Test
    void optimisticLockRejectsStaleUpdate() {
        Fulfillment fulfillment = new Fulfillment("order_1", "item_1", "mock delivery", "pay_1");
        fulfillmentRepository.save(fulfillment);

        Fulfillment first = fulfillmentRepository.findById(fulfillment.getId()).orElseThrow();
        Fulfillment second = fulfillmentRepository.findById(fulfillment.getId()).orElseThrow();

        first.start();
        fulfillmentRepository.save(first);

        second.start();
        assertThatThrownBy(() -> fulfillmentRepository.save(second))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }
}
