package com.payment.order.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderTimeoutScheduler;
import com.payment.order.application.OrderLine;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 支付成功回调的多支付单语义（Feature 015 / INV-1 / INV-2 / C5）：
 * ① 同一支付单重复回调 → 幂等吸收；② 换渠道后另一张支付单也回调成功 → 409 ORDER_NOT_PAYABLE
 * （payment-service 捕获后触发自动退款）；③ 订单已取消仍收到成功 → 409 ORDER_NOT_PAYABLE。
 * 修复 C5「订单非待支付态的 markPaid 异常被 payment 侧 catch(RuntimeException ignored) 静默吞掉」。
 */
class PaymentCallbackConflictScenarioTest {

    private OrderApplicationService service(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new OrderApplicationService(new InMemoryOrderRepository(), new InMemoryTransactionRepository(),
                client, request -> new CreatePaymentResponse("PM-0", "PROCESSING"),
                new NoopBusinessMetrics(), mock(OrderTimeoutScheduler.class));
    }

    private SuccessfulPurchaseScenarioTest.FakeCatalogClient clientWithSku() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = new SuccessfulPurchaseScenarioTest.FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 50);
        return client;
    }

    private static PaymentSucceededRequest succeeded(String orderNo, String paymentNo) {
        return new PaymentSucceededRequest(paymentNo, orderNo, "txn-x", "u1", 200L, "CNY");
    }

    @Test
    void duplicateCallbackForSamePaymentIsAbsorbed() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        OrderApplicationService service = service(client);
        String orderNo = service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-1").orderNo();

        service.onPaymentSucceeded(succeeded(orderNo, "PM-1"));
        service.onPaymentSucceeded(succeeded(orderNo, "PM-1")); // 同支付单重复回调

        assertThat(service.getOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(client.sold(1L)).isEqualTo(2L); // 库存只确认一次，不重复扣
    }

    @Test
    void anotherPaymentSucceedsOnPaidOrderThrowsNotPayable() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        OrderApplicationService service = service(client);
        String orderNo = service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-1").orderNo();
        service.onPaymentSucceeded(succeeded(orderNo, "PM-1")); // 渠道 A 成功，订单 PAID

        // 换渠道后另一张支付单（PM-2）也回调成功 → 多收了钱，必须 409 触发自动退款
        assertThatThrownBy(() -> service.onPaymentSucceeded(succeeded(orderNo, "PM-2")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.ORDER_NOT_PAYABLE));
        assertThat(service.getOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void successCallbackOnCancelledOrderThrowsNotPayable() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        OrderApplicationService service = service(client);
        String orderNo = service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-1").orderNo();
        Order order = service.getOrder(orderNo);
        order.cancel(); // 超时取消（PENDING_PAYMENT → CANCELLED）

        assertThatThrownBy(() -> service.onPaymentSucceeded(succeeded(order.getOrderNo(), "PM-1")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.ORDER_NOT_PAYABLE));
    }
}
