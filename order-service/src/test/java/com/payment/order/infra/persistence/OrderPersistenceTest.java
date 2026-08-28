package com.payment.order.infra.persistence;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单/交易持久化集成测试（H2，MySQL 兼容模式）：验证 PO↔领域映射、审计字段、乐观锁。
 */
@SpringBootTest
class OrderPersistenceTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void orderRoundTrip() {
        Order order = new Order("u1", "m1", "CNY",
                List.of(new OrderItem("1", "SKU-A", "Item A", 2, 100, "CNY")));
        order.confirm();
        orderRepository.save(order);

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(order.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(reloaded.getTotalMinor()).isEqualTo(200L);
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().get(0).getSkuCode()).isEqualTo("SKU-A");
        assertThat(reloaded.getVersion()).isEqualTo(1);
    }

    @Test
    void optimisticLockRejectsStaleUpdate() {
        Order order = new Order("u1", "m1", "CNY",
                List.of(new OrderItem("1", "SKU-A", "Item A", 2, 100, "CNY")));
        order.confirm();
        orderRepository.save(order);

        Order first = orderRepository.findById(order.getId()).orElseThrow();
        Order second = orderRepository.findById(order.getId()).orElseThrow();

        first.markPaid(1L);
        orderRepository.save(first);

        second.markPaid(1L);
        assertThatThrownBy(() -> orderRepository.save(second))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void transactionRoundTrip() {
        Transaction tx = new Transaction("100", 200L, "CNY", "PURCHASE");
        transactionRepository.save(tx);

        Transaction reloaded = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(reloaded.getOrderId()).isEqualTo("100");
        assertThat(reloaded.getAmountMinor()).isEqualTo(200L);
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(transactionRepository.findByOrderId("100")).isPresent();
    }
}
