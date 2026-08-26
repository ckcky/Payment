package com.payment.order.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态机测试（T022）：状态转换、取消与金额约束。
 */
class OrderStateMachineTest {

    private static Order order(long unitPrice, int qty) {
        return new Order("u1", "m1", "CNY",
                List.of(new OrderItem("1", "S1", "item", qty, unitPrice, "CNY")));
    }

    @Test
    void confirmMovesToPendingPayment() {
        Order order = order(100, 2);
        order.confirm();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void fullPaymentMovesToPaid() {
        Order order = order(100, 2); // total 200
        order.confirm();
        assertThat(order.markPaid(200)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidMinor()).isEqualTo(200L);
    }

    @Test
    void partialThenFullPayment() {
        Order order = order(100, 2); // total 200
        order.confirm();
        assertThat(order.markPaid(50)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_PAID);
        assertThat(order.markPaid(150)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void overPaymentRejected() {
        Order order = order(100, 2); // total 200
        order.confirm();
        assertThatThrownBy(() -> order.markPaid(201))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION));
    }

    @Test
    void cancelFromPendingPayment() {
        Order order = order(100, 1);
        order.confirm();
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelFromPaidThrows() {
        Order order = order(100, 1);
        order.confirm();
        order.markPaid(100);
        assertThatThrownBy(order::cancel)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void fullLifecycleToClosed() {
        Order order = order(100, 1);
        order.confirm();
        order.markPaid(100);
        order.markFulfilling();
        order.complete();
        order.close();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
    }
}
