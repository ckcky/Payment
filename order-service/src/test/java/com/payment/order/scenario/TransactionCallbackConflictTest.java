package com.payment.order.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.order.application.FulfillmentGateway;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.OrderTimeoutScheduler;
import com.payment.order.application.PaymentGateway;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.application.TransactionApplicationService;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 重复 / 超额支付冲突场景（order transaction 层视角，Feature 016 / ADR-0054，迁移自
 * PaymentCallbackConflictScenarioTest 的 payment 视角）：
 * ① 同一支付单重复回调 → 幂等吸收；② 第二张支付单成功 → transaction 层判定 surplus，
 * 以 {@code transactionNo + paymentNo} 发起自动退款；③ 已取消订单收到成功 → 同样 surplus 退款；
 * 全程 <b>0 次</b>抛 {@code ORDER_NOT_PAYABLE}（FR-007 / SC-001）。
 */
class TransactionCallbackConflictTest {

    private final InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final RecordingFulfillmentGateway fulfillmentGateway = new RecordingFulfillmentGateway();
    private final StubPaymentGateway paymentGateway = new StubPaymentGateway();

    private OrderApplicationService orderLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new OrderApplicationService(orderRepository, transactionRepository, client, paymentGateway,
                new NoopBusinessMetrics(), Mockito.mock(OrderTimeoutScheduler.class), fulfillmentGateway);
    }

    private TransactionApplicationService transactionLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new TransactionApplicationService(orderRepository, orderLayer(client),
                paymentGateway, new NoopBusinessMetrics(), new StructuredAuditLogger());
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
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = orderLayer(client).createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-1").orderNo();

        service.onPaymentSucceeded(succeeded(orderNo, "PM-1"));
        service.onPaymentSucceeded(succeeded(orderNo, "PM-1")); // 同支付单重复回调

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fulfillmentGateway.succeededRequests).hasSize(1); // 履约只驱动一次
        assertThat(paymentGateway.refundRequests).isEmpty();          // 幂等吸收，不退款
    }

    @Test
    void secondPaymentOnPaidOrderIsJudgedSurplusAndRefunded() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = orderLayer(client).createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-1").orderNo();
        service.onPaymentSucceeded(succeeded(orderNo, "PM-1")); // 渠道 A 成功，订单 PAID

        // 换渠道后另一张支付单（PM-2）也回调成功 → transaction 层判定 surplus → 自动退款（不抛 409）
        service.onPaymentSucceeded(succeeded(orderNo, "PM-2"));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentGateway.refundRequests).hasSize(1);
        RefundCommandRequest refund = paymentGateway.refundRequests.get(0);
        assertThat(refund.paymentNo()).isEqualTo("PM-2");
        assertThat(refund.transactionNo()).isEqualTo("txn-x"); // FR-005：以 transactionNo + paymentNo 发起
        assertThat(fulfillmentGateway.succeededRequests).hasSize(1); // surplus 不驱动第二次履约
    }

    @Test
    void successOnCancelledOrderIsRefundedWithoutNotPayableException() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        OrderApplicationService orderLayer = orderLayer(client);
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = orderLayer.createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-1").orderNo();
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.cancel(); // 超时取消（PENDING_PAYMENT → CANCELLED）
        orderRepository.save(order);

        // 已取消订单收到成功 → surplus 退款；order 对 payment 不抛 ORDER_NOT_PAYABLE（FR-007）
        service.onPaymentSucceeded(succeeded(order.getOrderNo(), "PM-1"));

        assertThat(paymentGateway.refundRequests).hasSize(1);
        assertThat(paymentGateway.refundRequests.get(0).paymentNo()).isEqualTo("PM-1");
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void normalPathDelegatesToOrderLayerWhichDrivesFulfillment() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        OrderApplicationService orderLayer = orderLayer(client);
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = orderLayer.createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-1").orderNo();

        service.onPaymentSucceeded(succeeded(orderNo, "PM-1"));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fulfillmentGateway.succeededRequests).hasSize(1); // order 层驱动履约（FR-003）
        assertThat(paymentGateway.refundRequests).isEmpty(); // 正常路径不触发退款
    }

    /** 桩支付网关：记录退款命令（surplus 路径），创建支付返回固定响应。 */
    private static final class StubPaymentGateway implements PaymentGateway {

        final List<RefundCommandRequest> refundRequests = new ArrayList<>();

        @Override
        public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
            return new CreatePaymentResponse("PM-0", "PROCESSING");
        }

        @Override
        public RefundCommandResponse refund(RefundCommandRequest request) {
            refundRequests.add(request);
            return new RefundCommandResponse("RF-STUB", "SUCCEEDED");
        }
    }

    /** 记录型履约网关：order 层驱动履约。 */
    private static final class RecordingFulfillmentGateway implements FulfillmentGateway {

        final List<PaymentSucceededRequest> succeededRequests = new ArrayList<>();

        @Override
        public com.payment.common.dto.rpc.FulfillmentAcceptedResponse notifyPaymentSucceeded(
                PaymentSucceededRequest request) {
            succeededRequests.add(request);
            return new com.payment.common.dto.rpc.FulfillmentAcceptedResponse(1L, "PROCESSING");
        }
    }
}
