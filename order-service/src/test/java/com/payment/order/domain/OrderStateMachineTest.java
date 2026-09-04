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
        assertThat(order.markPaid("PM-1")).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidMinor()).isEqualTo(200L);
        assertThat(order.getPaymentNo()).isEqualTo("PM-1");
    }

    @Test
    void repeatedPaidCallbackIsAbsorbed() {
        Order order = order(100, 2); // total 200
        order.confirm();
        order.markPaid("PM-1");
        assertThat(order.markPaid("PM-2")).isFalse(); // 幂等重复回调，吸收且不覆盖 paymentNo
        assertThat(order.getPaymentNo()).isEqualTo("PM-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
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
        order.markPaid("PM-1");
        assertThatThrownBy(order::cancel)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void fullLifecycleToClosed() {
        Order order = order(100, 1);
        order.confirm();
        order.markPaid("PM-1");
        order.markFulfilling();
        order.complete();
        order.close();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
    }
}
