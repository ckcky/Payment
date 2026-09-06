package com.payment.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.order.application.FulfillmentGateway;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.TransactionStatus;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 订单应用服务单测（002 T022）：下单价格快照与库存预占、支付成功回写
 * （markPaid + Transaction.succeed + confirmStock）、重复回调幂等、
 * 非法前态（他单已付 / 已取消）明确 409（Feature 015 语义，供自动退款）。
 */
class OrderApplicationServiceTest {

    private final InMemoryOrderRepository orders = new InMemoryOrderRepository();
    private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    private final RecordingCatalogClient catalog = new RecordingCatalogClient();

    private OrderApplicationService service() {
        // 时间轮登记为空操作：单测不接 Redis（schedule 之外无其他 Redis 依赖）
        OrderTimeoutScheduler noopScheduler = new OrderTimeoutScheduler(
                null, orders, catalog, new OrderTimeoutProperties(), new NoopBusinessMetrics()) {
            @Override
            public void schedule(Long orderId) {
                // no-op
            }
        };
        return new OrderApplicationService(orders, transactions, catalog,
                new StubPaymentGateway(), new NoopBusinessMetrics(), noopScheduler,
                new RecordingFulfillmentGateway());
    }

    private String newPendingPaymentOrder(OrderApplicationService service) {
        return service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idem-1").orderNo();
    }

    @Test
    void createOrderSnapshotsPriceAndReservesStockPerLine() {
        CreateOrderResult result = service().createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2), new OrderLine(2L, 1)), "idem-1");

        assertThat(result.orderNo()).isNotBlank();
        assertThat(result.totalMinor()).isEqualTo(7500L); // 2500 * 2 + 2500 * 1（价格快照累加）
        assertThat(result.currencyCode()).isEqualTo("CNY");
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // 预占幂等键：order:{orderNo}:sku:{skuId}（ADR-0063）
        assertThat(catalog.reserved).containsExactly(
                "order:" + result.orderNo() + ":sku:1",
                "order:" + result.orderNo() + ":sku:2");
        // Feature 015：下单不再同步建支付单
        assertThat(result.paymentNo()).isNull();
    }

    @Test
    void seckillDenialRejectsOrderWithoutReserving() {
        catalog.seckillAllowed = false;

        assertThatThrownBy(() -> service().createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idem-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("seckill stock insufficient");
        assertThat(catalog.reserved).isEmpty();
    }

    @Test
    void onPaymentSucceededMarksOrderPaidAndConfirmsStock() {
        OrderApplicationService service = service();
        String orderNo = newPendingPaymentOrder(service);

        service.onPaymentSucceeded(PaymentSucceededRequest.withoutItems("PM-1", orderNo, "txn-1", "u1", 5000L, "CNY"));

        Order order = service.getOrder(orderNo);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidMinor()).isEqualTo(order.getTotalMinor());
        assertThat(order.getPaymentNo()).isEqualTo("PM-1");
        assertThat(transactions.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.SUCCEEDED);
        // 确认扣减：幂等键 = 预占键 + deductId=支付单号
        assertThat(catalog.confirmed).containsExactly("order:" + orderNo + ":sku:1:PM-1");
    }

    @Test
    void duplicateSuccessCallbackIsAbsorbedWithoutDoubleConfirm() {
        OrderApplicationService service = service();
        String orderNo = newPendingPaymentOrder(service);
        PaymentSucceededRequest request =
                PaymentSucceededRequest.withoutItems("PM-1", orderNo, "txn-1", "u1", 5000L, "CNY");

        service.onPaymentSucceeded(request);
        service.onPaymentSucceeded(request); // 同一支付单重复回调

        assertThat(catalog.confirmed).hasSize(1);
        assertThat(service.getOrder(orderNo).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void successFromAnotherPaymentBypassingTransactionLayerFailsFast() {
        OrderApplicationService service = service();
        String orderNo = newPendingPaymentOrder(service);
        service.onPaymentSucceeded(PaymentSucceededRequest.withoutItems("PM-1", orderNo, "txn-1", "u1", 5000L, "CNY"));

        // Feature 016 / ADR-0054：surplus 判定与自动退款发起已上移到 transaction 层。
        // 绕过它直接调 order 层属内部契约违规 → INTERNAL_ERROR 快速失败，
        // MUST NOT 再抛 ORDER_NOT_PAYABLE（payment 侧已无法据此触发退款，409 会被静默吞掉）。
        assertThatThrownBy(() -> service.onPaymentSucceeded(
                PaymentSucceededRequest.withoutItems("PM-2", orderNo, "txn-1", "u1", 5000L, "CNY")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.INTERNAL_ERROR));
        assertThat(service.getOrder(orderNo).getPaymentNo()).isEqualTo("PM-1");
    }

    @Test
    void successOnCancelledOrderBypassingTransactionLayerFailsFast() {
        OrderApplicationService service = service();
        String orderNo = newPendingPaymentOrder(service);
        service.releaseStockForOrder(orderNo); // 超时/失败路径：释放并取消订单
        assertThat(service.getOrder(orderNo).getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // 同上：正常链路下 transaction 层应先判定为 surplus 并以 transactionNo+paymentNo 发起退款，
        // 不会走到本层；直接调用则快速失败，避免多收的钱无人处理。
        assertThatThrownBy(() -> service.onPaymentSucceeded(
                PaymentSucceededRequest.withoutItems("PM-1", orderNo, "txn-1", "u1", 5000L, "CNY")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.INTERNAL_ERROR));
        assertThat(service.getOrder(orderNo).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    /** 记录式 catalog fake：捕获三段式库存命令与秒杀开关。 */
    private static final class RecordingCatalogClient implements CatalogClient {

        final List<String> reserved = new ArrayList<>();
        final List<String> confirmed = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        boolean seckillAllowed = true;

        @Override
        public SkuSnapshot getSku(Long skuId) {
            return new SkuSnapshot(skuId, "SKU-" + skuId, "商品" + skuId, 2500L, "CNY", true);
        }

        @Override
        public void reserveStock(ReserveStockCommand request) {
            reserved.add(request.reservationId());
        }

        @Override
        public void confirmStock(ConfirmStockCommand request) {
            confirmed.add(request.reservationId() + ":" + request.deductId());
        }

        @Override
        public void releaseStock(ReleaseStockCommand request) {
            released.add(request.reservationId());
        }

        @Override
        public SeckillResult trySeckillDeduct(Long skuId, long quantity) {
            return new SeckillResult(seckillAllowed, 10, true);
        }

        @Override
        public void rollbackSeckill(Long skuId, long quantity) {
            // 记录式：无需断言
        }
    }

    /** 桩支付网关：返回固定支付单号。 */
    private static final class StubPaymentGateway implements PaymentGateway {

        @Override
        public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
            return new CreatePaymentResponse("PM-STUB", "CREATED", null, 1, "mock");
        }

        @Override
        public com.payment.common.dto.rpc.RefundCommandResponse refund(
                com.payment.common.dto.rpc.RefundCommandRequest request) {
            throw new UnsupportedOperationException("stub: refund not expected");
        }
    }

    /** 记录型履约网关：order 层驱动履约（Feature 016 / FR-003）。 */
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
