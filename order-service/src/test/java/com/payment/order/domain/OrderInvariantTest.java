package com.payment.order.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单金额与快照不变量测试（T031）：总额=明细小计之和；已支付≤总额；已退款≤已支付；快照冻结。
 */
class OrderInvariantTest {

    @Test
    void totalEqualsSumOfSubtotals() {
        Order order = new Order("u1", "m1", "CNY", List.of(
                new OrderItem("OI-TEST-1", "1", "A", "item a", 2, 100, "CNY"),
                new OrderItem("OI-TEST-2", "2", "B", "item b", 3, 50, "CNY")));
        assertThat(order.getTotalMinor()).isEqualTo(350L); // 200 + 150
    }

    @Test
    void itemCurrencyMismatchRejected() {
        assertThatThrownBy(() -> new Order("u1", "m1", "CNY", List.of(
                new OrderItem("OI-TEST-3", "1", "A", "item a", 1, 100, "USD"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyItemsRejected() {
        assertThatThrownBy(() -> new Order("u1", "m1", "CNY", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundCannotExceedPaid() {
        Order order = new Order("u1", "m1", "CNY",
                List.of(new OrderItem("OI-TEST-4", "1", "A", "item a", 2, 100, "CNY")));
        order.confirm();
        order.markPaid("PM-1"); // 整单支付，paidMinor = totalMinor = 200
        order.recordRefund(199);
        assertThat(order.getRefundedMinor()).isEqualTo(199L);
        assertThat(order.getRefundableMinor()).isEqualTo(1L);
        assertThatThrownBy(() -> order.recordRefund(2))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION));
    }

    @Test
    void snapshotIsImmutableAfterCreation() {
        Order order = new Order("u1", "m1", "CNY",
                List.of(new OrderItem("OI-TEST-5", "1", "A", "item a", 2, 100, "CNY")));
        assertThat(order.getTotalMinor()).isEqualTo(200L);
        assertThatThrownBy(() -> order.getItems().add(
                new OrderItem("OI-TEST-6", "2", "B", "item b", 1, 1, "CNY")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
