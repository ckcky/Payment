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
                new NoopBusinessMetrics(), Mockito.mock(OrderTimeoutScheduler.class), fulfillmentGateway,
                new com.payment.order.application.NoopTransactionManager(),
                com.payment.order.application.MqTestSupport.off());
    }

    private TransactionApplicationService transactionLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new TransactionApplicationService(orderRepository, transactionRepository, refundRepository,
                orderLayer(client), paymentGateway, fulfillmentGateway, client,
                new NoopBusinessMetrics(), new StructuredAuditLogger(),
                com.payment.order.application.MqTestSupport.off(),
                new com.payment.order.application.NoopTransactionManager());
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

    // =========================================================================
    // spec 030 / B7（T14~T16）：在途守卫区分「重放 / 重试」
    // =========================================================================

    /**
     * FR-230 / FR-235 / SC-B7-01：<b>渠道已受理</b>（TXRF = {@code PROCESSING}，已有 PMRF）
     * ⇒ 再次提交是<b>重放</b>，渠道请求次数 <b>= 1</b>（不重复调 payment）。
     *
     * <p>这是 ① 回放路径的正向固化：修复前 {@code REQUESTED} 与 {@code PROCESSING} 被一视同仁，
     * 本用例在修复前后都应通过——它保证修复<b>没有把已受理的单误判成可重试</b>（那会双重退款）。</p>
     */
    @Test
    void acceptedRefundIsReplayedWithExactlyOneChannelCall() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        RefundOrder first = service.createRefund(orderNo, null, 100L, "MANUAL");
        assertThat(first.getStatus()).isEqualTo(RefundOrderStatus.PROCESSING);
        assertThat(first.getPaymentRefundNo()).isNotNull(); // 已受理

        RefundOrder second = service.createRefund(orderNo, null, 100L, "MANUAL");

        assertThat(second.getRefundNo()).isEqualTo(first.getRefundNo());
        assertThat(paymentGateway.refundRequests).hasSize(1); // 正确回放：不重复调渠道
    }

    /**
     * FR-230 / FR-235 / SC-B7-01：<b>渠道未受理</b>（首调失败 ⇒ TXRF 留 {@code REQUESTED}
     * 且 {@code paymentRefundNo == null}）⇒ 再次提交是<b>重试</b>，MUST 用<b>同一 TXRF</b>
     * 重放渠道调用，渠道请求次数 <b>= 2</b>，且<b>不得产生第二张 TXRF</b>。
     *
     * <p>⚠️ 本用例断言的是<b>被此前实现固化的错误预期</b>：修复前在途守卫把 {@code REQUESTED}
     * 一律当作「回放」直接返回 ⇒ 首调失败的单永久卡死，渠道请求次数恒为 1、退款永不推进。
     * 缺陷证据链见 {@code design-review.md §11 C-18}；此处是<b>修正错误预期</b>，
     * 而非放宽断言迎合实现。</p>
     */
    @Test
    void unacceptedRefundIsRetriedWithSameTxrfAndSecondChannelCall() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        paymentGateway.failFirstNCalls = 1; // 渠道首调失败

        // 首调：渠道失败 ⇒ TXRF 落库为 REQUESTED、paymentRefundNo 为 null，异常向上抛出
        assertThatThrownBy(() -> service.createRefund(orderNo, null, 100L, "MANUAL"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(paymentGateway.refundRequests).hasSize(1);

        List<RefundOrder> afterFailure = refundRepository.findByOrderNo(orderNo);
        assertThat(afterFailure).hasSize(1);
        RefundOrder stranded = afterFailure.get(0);
        assertThat(stranded.getStatus()).isEqualTo(RefundOrderStatus.REQUESTED);
        assertThat(stranded.getPaymentRefundNo()).isNull(); // 渠道未受理

        // 重试：MUST 复用同一 TXRF 重放渠道调用（不得新建第二张 TXRF）
        RefundOrder retried = service.createRefund(orderNo, null, 100L, "MANUAL");

        assertThat(paymentGateway.refundRequests).hasSize(2);           // 首调失败 + 重试
        assertThat(retried.getRefundNo()).isEqualTo(stranded.getRefundNo()); // 同号复用
        assertThat(retried.getStatus()).isEqualTo(RefundOrderStatus.PROCESSING);
        assertThat(retried.getPaymentRefundNo()).isNotNull();           // 本次受理成功
        // 不产生第二个 TXRF（双重退款防线）
        assertThat(refundRepository.findByOrderNo(orderNo)).hasSize(1);
    }

    /**
     * SC-B7-03：并发两次同参退款 ⇒ 最终<b>只产生一个</b> TXRF + 一个 PMRF（三层防线）。
     *
     * <p>在途守卫位于 {@code synchronized (orderNo.intern())} 内（spec 019），
     * 配合「复用而非新建」的修复，重复提交不会落出第二张退款单。</p>
     */
    @Test
    void duplicateRefundProducesSingleTxrfAndSinglePmrf() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        RefundOrder r1 = service.createRefund(orderNo, null, 100L, "MANUAL");
        RefundOrder r2 = service.createRefund(orderNo, null, 100L, "MANUAL");
        RefundOrder r3 = service.createRefund(orderNo, null, 100L, "MANUAL");

        assertThat(r2.getRefundNo()).isEqualTo(r1.getRefundNo());
        assertThat(r3.getRefundNo()).isEqualTo(r1.getRefundNo());
        assertThat(refundRepository.findByOrderNo(orderNo)).hasSize(1); // 只一个 TXRF
        assertThat(paymentGateway.refundRequests).hasSize(1);            // 只一个 PMRF
        assertThat(r3.getPaymentRefundNo()).isEqualTo(r1.getPaymentRefundNo());
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
    void failedCallbackPersistsFailureReason() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        RefundOrder refundOrder = service.createRefund(orderNo, null, 100L, "MANUAL");
        service.onRefundResult(new RefundResultNotification(refundOrder.getRefundNo(),
                refundOrder.getPaymentRefundNo(), "txn-x", orderNo, "PM-1",
                100L, "CNY", "FAILED", "channel declined"));

        RefundOrder reloaded = refundRepository.findByRefundNo(refundOrder.getRefundNo()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundOrderStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("channel declined"); // 失败原因随终态落库
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID); // 退款失败订单状态不动
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
        /**
         * 前 N 次 {@code refund} 调用抛异常（模拟渠道首调失败 / 超时，本地单留 REQUESTED
         * 且 {@code paymentRefundNo == null}）——供 spec 030 / B7 的「重放 vs 重试」断言使用。
         */
        int failFirstNCalls = 0;

        @Override
        public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
            return new CreatePaymentResponse("PM-STUB", "PROCESSING");
        }

        @Override
        public RefundCommandResponse refund(RefundCommandRequest request) {
            refundRequests.add(request);
            if (refundRequests.size() <= failFirstNCalls) {
                throw new IllegalStateException("channel refund failed (injected)");
            }
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
