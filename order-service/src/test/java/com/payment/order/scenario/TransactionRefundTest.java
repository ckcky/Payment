package com.payment.order.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.order.application.FulfillmentGateway;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.OrderTimeoutScheduler;
import com.payment.order.application.PaymentGateway;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.application.TransactionApplicationService;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.RefundOrderStatus;
import com.payment.order.domain.Transaction;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRefundRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 退款链路端到端场景（order transaction 层视角，spec 019 / ADR-0067，T106）：
 * TXRF 落单 → payment 受理（PMRF 回填）→ on-refund-result 收口
 * （refunded_minor 累加 + 订单退款态 + 秒杀回补 + 履约终止）→ 幂等重放。
 */
class TransactionRefundTest {

    private final InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final InMemoryTransactionRefundRepository refundRepository = new InMemoryTransactionRefundRepository();
    private final RecordingFulfillmentGateway fulfillmentGateway = new RecordingFulfillmentGateway();
    private final StubPaymentGateway paymentGateway = new StubPaymentGateway();

    private OrderApplicationService orderLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new OrderApplicationService(orderRepository, transactionRepository, client, paymentGateway,
                new NoopBusinessMetrics(), Mockito.mock(OrderTimeoutScheduler.class), fulfillmentGateway);
    }

    private TransactionApplicationService transactionLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new TransactionApplicationService(orderRepository, transactionRepository, refundRepository,
                orderLayer(client), paymentGateway, fulfillmentGateway, client,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    /** 已支付订单（SKU-A x2 = 200 分），返回 orderNo。 */
    private String paidOrder(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = orderLayer(client).createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-1").orderNo();
        service.onPaymentSucceeded(succeeded(orderNo, "PM-1"));
        return orderNo;
    }

    private static PaymentSucceededRequest succeeded(String orderNo, String paymentNo) {
        return PaymentSucceededRequest.withoutItems(paymentNo, orderNo, "txn-x", "u1", 200L, "CNY");
    }

    private SuccessfulPurchaseScenarioTest.FakeCatalogClient clientWithSku() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = new SuccessfulPurchaseScenarioTest.FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 50);
        return client;
    }

    private RefundResultNotification notification(String txrf, String pmrf, String orderNo, String paymentNo,
                                                  long amountMinor, String status) {
        return new RefundResultNotification(txrf, pmrf, "txn-x", orderNo, paymentNo,
                amountMinor, "CNY", status, null);
    }

    @Test
    void manualRefundCreatesTxrfAndBackfillsPmrf() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        String orderNo = paidOrder(client);

        RefundOrder refundOrder = transactionLayer(client).createRefund(orderNo, null, 100L, "MANUAL");

        assertThat(refundOrder.getRefundNo()).startsWith("TXRF");
        assertThat(refundOrder.getPaymentRefundNo()).startsWith("PMRF"); // 双号互记
        assertThat(refundOrder.getStatus()).isEqualTo(RefundOrderStatus.PROCESSING);
        assertThat(paymentGateway.refundRequests).hasSize(1);
        assertThat(paymentGateway.refundRequests.get(0).transactionRefundNo()).isEqualTo(refundOrder.getRefundNo());
        assertThat(refundRepository.findByIdempotencyKey(refundOrder.getRefundNo())).isPresent();
    }

    @Test
    void inFlightRefundIsReplayedNotDuplicated() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        RefundOrder first = service.createRefund(orderNo, null, 100L, "MANUAL");
        RefundOrder second = service.createRefund(orderNo, null, 100L, "MANUAL"); // 在途重放

        assertThat(second.getRefundNo()).isEqualTo(first.getRefundNo());
        assertThat(paymentGateway.refundRequests).hasSize(1); // 不重复调 payment
    }

    @Test
    void refundExceedingRefundableIsRejected() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        String orderNo = paidOrder(client);

        assertThatThrownBy(() -> transactionLayer(client).createRefund(orderNo, null, 300L, "MANUAL"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION);
    }

    @Test
    void refundOnPendingPaymentOrderIsRejected() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        String orderNo = orderLayer(client).createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-1").orderNo();

        assertThatThrownBy(() -> transactionLayer(client).createRefund(orderNo, null, 100L, "MANUAL"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION);
    }

    @Test
    void succeededCallbackAccumulatesAndAdvancesOrderStatus() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);
        RefundOrder refundOrder = service.createRefund(orderNo, null, 100L, "MANUAL");

        service.onRefundResult(notification(refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(),
                orderNo, "PM-1", 100L, "SUCCEEDED"));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        Transaction transaction = transactionRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(transaction.getRefundedMinor()).isEqualTo(100L);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getRefundedMinor()).isEqualTo(100L);
        assertThat(fulfillmentGateway.refundRequests).hasSize(1); // 履约终止
    }

    @Test
    void fullRefundMarksOrderRefunded() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);
        RefundOrder refundOrder = service.createRefund(orderNo, null, 200L, "MANUAL");

        service.onRefundResult(notification(refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(),
                orderNo, "PM-1", 200L, "SUCCEEDED"));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void duplicateCallbackIsAbsorbedWithoutDoubleAccumulation() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);
        RefundOrder refundOrder = service.createRefund(orderNo, null, 100L, "MANUAL");
        RefundResultNotification n = notification(refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(),
                orderNo, "PM-1", 100L, "SUCCEEDED");

        service.onRefundResult(n);
        service.onRefundResult(n); // 重复回调
        service.onRefundResult(notification(refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(),
                orderNo, "PM-1", 100L, "FAILED")); // 终态冲突不回退

        assertThat(transactionRepository.findByOrderNo(orderNo).orElseThrow().getRefundedMinor())
                .isEqualTo(100L); // 只累加一次
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PARTIALLY_REFUNDED); // 不回退
        assertThat(fulfillmentGateway.refundRequests).hasSize(1); // 履约只终止一次
    }

    @Test
    void surplusRefundClosesWithoutBooking() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        // 第二张支付单（PM-2）也成功 → surplus 退款；终态回调后不累加（从未进账）
        service.onPaymentSucceeded(succeeded(orderNo, "PM-2"));
        RefundOrder surplusRefund = refundRepository.findByOrderNo(orderNo).stream()
                .filter(r -> r.getPaymentNo().equals("PM-2")).findFirst().orElseThrow();

        service.onRefundResult(notification(surplusRefund.getRefundNo(), surplusRefund.getPaymentRefundNo(),
                orderNo, "PM-2", 200L, "SUCCEEDED"));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID); // 订单状态不动
        assertThat(transactionRepository.findByOrderNo(orderNo).orElseThrow().getRefundedMinor())
                .isEqualTo(0L); // 不累加
        assertThat(fulfillmentGateway.refundRequests).isEmpty(); // 不终止正常履约
    }

    @Test
    void seckillRestockTriggersOnlyForSeededSku() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        client.seedSku(new SkuSnapshot(2L, "SKU-B", "Item B", 100, "CNY", true), 50);
        client.seedSeckill(1L, 30L); // SKU-A 播种秒杀配额（SKU-B 普通品）
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = orderLayer(client).createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2), new OrderLine(2L, 1)), "idk-1").orderNo();
        service.onPaymentSucceeded(PaymentSucceededRequest.withoutItems("PM-1", orderNo, "txn-x", "u1",
                300L, "CNY"));
        long seckillAfterDeduct = client.seckillRemaining(1L);

        RefundOrder refundOrder = service.createRefund(orderNo, null, 300L, "MANUAL");
        service.onRefundResult(notification(refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(),
                orderNo, "PM-1", 300L, "SUCCEEDED"));

        assertThat(client.seckillRemaining(1L)).isEqualTo(seckillAfterDeduct + 2); // 秒杀回补
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    // ---- fakes ----

    /** 桩支付网关：记录退款命令并受理（PMRF + PROCESSING）。 */
    private static final class StubPaymentGateway implements PaymentGateway {
        final List<RefundCommandRequest> refundRequests = new ArrayList<>();

        @Override
        public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
            return new CreatePaymentResponse("PM-STUB", "PROCESSING");
        }

        @Override
        public RefundCommandResponse refund(RefundCommandRequest request) {
            refundRequests.add(request);
            return new RefundCommandResponse("PMRF-" + request.transactionRefundNo(), "PROCESSING");
        }
    }

    /** 记录型履约网关。 */
    private static final class RecordingFulfillmentGateway implements FulfillmentGateway {
        final List<PaymentSucceededRequest> succeededRequests = new ArrayList<>();
        final List<RefundFulfillmentRequest> refundRequests = new ArrayList<>();

        @Override
        public com.payment.common.dto.rpc.FulfillmentAcceptedResponse notifyPaymentSucceeded(
                PaymentSucceededRequest request) {
            succeededRequests.add(request);
            return new com.payment.common.dto.rpc.FulfillmentAcceptedResponse(1L, "PROCESSING");
        }

        @Override
        public com.payment.common.dto.rpc.RefundFulfillmentResponse onRefund(
                RefundFulfillmentRequest request) {
            refundRequests.add(request);
            return new com.payment.common.dto.rpc.RefundFulfillmentResponse(request.refundNo(), "CANCELLED");
        }
    }
}
