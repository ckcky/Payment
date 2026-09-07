package com.payment.order.scenario;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.order.application.CatalogClient;
import com.payment.order.application.ConfirmStockCommand;
import com.payment.order.application.CreateOrderResult;
import com.payment.order.application.FulfillmentGateway;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.PaymentGateway;
import com.payment.order.application.ReleaseStockCommand;
import com.payment.order.application.ReserveStockCommand;
import com.payment.order.application.SeckillResult;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.application.OrderTimeoutScheduler;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionStatus;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成功购买端到端场景（订单侧）：SKU RPC 校验 + 价格快照 + Order 1:1 Transaction +
 * 创建支付意图的同步 RPC + 库存三段式（下单预占 → 支付成功确认扣减）。
 *
 * <p>用内存 {@link CatalogClient} 作为 catalog-service 同步 RPC 的替身（含库存视图），
 * 用记录型 fake {@link PaymentGateway} 作为 payment-service 同步 RPC 的替身，验证订单服务侧编排。</p>
 */
class SuccessfulPurchaseScenarioTest {

    private final InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final FakePaymentGateway paymentGateway = new FakePaymentGateway();

    private final RecordingFulfillmentGateway fulfillmentGateway = new RecordingFulfillmentGateway();

    private OrderApplicationService service(CatalogClient client) {
        return new OrderApplicationService(orderRepository, transactionRepository, client, paymentGateway,
                new NoopBusinessMetrics(), org.mockito.Mockito.mock(OrderTimeoutScheduler.class),
                fulfillmentGateway);
    }

    /** 记录型 fake：捕获创建支付意图请求并返回固定响应。 */
    private static final class FakePaymentGateway implements PaymentGateway {
        final List<CreatePaymentRequest> requests = new java.util.ArrayList<>();

        @Override
        public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
            requests.add(request);
            return new CreatePaymentResponse("PM-1", "PROCESSING");
        }

        @Override
        public com.payment.common.dto.rpc.RefundCommandResponse refund(
                com.payment.common.dto.rpc.RefundCommandRequest request) {
            throw new UnsupportedOperationException("scenario fake: refund not expected");
        }
    }

    /** 记录型 fake：order 层驱动履约的出站端口替身（Feature 016 / FR-003）。 */
    private static final class RecordingFulfillmentGateway implements FulfillmentGateway {
        final List<PaymentSucceededRequest> succeededRequests = new java.util.ArrayList<>();

        @Override
        public com.payment.common.dto.rpc.FulfillmentAcceptedResponse notifyPaymentSucceeded(
                PaymentSucceededRequest request) {
            succeededRequests.add(request);
            return new com.payment.common.dto.rpc.FulfillmentAcceptedResponse(1L, "PROCESSING");
        }

        @Override
        public com.payment.common.dto.rpc.RefundFulfillmentResponse onRefund(
                com.payment.common.dto.rpc.RefundFulfillmentRequest request) {
            throw new UnsupportedOperationException("scenario fake: refund not expected");
        }
    }

    /**
     * 内存 catalog 替身：维护每个 SKU 的库存视图（total/available/reserved/sold）与预占记录，
     * 模拟 catalog-service 的三段式语义，供订单侧编排测试。
     */
    static final class FakeCatalogClient implements CatalogClient {
        private final Map<Long, SkuSnapshot> skus = new HashMap<>();
        private final Map<Long, long[]> stock = new HashMap<>(); // [available, reserved, sold]
        private final Map<String, Long> reservations = new HashMap<>(); // reservationId -> skuId
        private final Set<Long> releasedReservations = new HashSet<>();
        private final Set<String> confirmedDeductIds = new HashSet<>();
        private final Map<Long, Long> seckillStock = new HashMap<>(); // skuId -> 剩余秒杀配额（仅播种过的 SKU）

        void seedSku(SkuSnapshot sku, long total) {
            skus.put(sku.skuId(), sku);
            stock.put(sku.skuId(), new long[]{total, 0L, 0L});
        }

        void seedSeckill(Long skuId, long total) {
            seckillStock.put(skuId, total);
        }

        long seckillRemaining(Long skuId) {
            return seckillStock.getOrDefault(skuId, 0L);
        }

        long available(Long skuId) {
            return stock.get(skuId)[0];
        }

        long sold(Long skuId) {
            return stock.get(skuId)[2];
        }

        @Override
        public SkuSnapshot getSku(Long skuId) {
            SkuSnapshot sku = skus.get(skuId);
            if (sku == null) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "sku not found: " + skuId);
            }
            return sku;
        }

        @Override
        public void reserveStock(ReserveStockCommand request) {
            long[] s = stock.get(request.skuId());
            if (s[0] < request.quantity()) {
                throw BizException.of(ErrorCodes.CONFLICT, "insufficient stock sku=" + request.skuId());
            }
            s[0] -= request.quantity();
            s[1] += request.quantity();
            reservations.put(request.reservationId(), request.skuId());
        }

        @Override
        public void confirmStock(ConfirmStockCommand request) {
            if (confirmedDeductIds.contains(request.deductId())) {
                return; // 幂等
            }
            long[] s = stock.get(request.skuId());
            s[1] -= request.quantity();
            s[2] += request.quantity();
            confirmedDeductIds.add(request.deductId());
        }

        @Override
        public void releaseStock(ReleaseStockCommand request) {
            Long skuId = reservations.get(request.reservationId());
            if (skuId == null || releasedReservations.contains(hash(request.reservationId()))) {
                return; // 幂等
            }
            long[] s = stock.get(skuId);
            s[1] -= request.quantity();
            s[0] += request.quantity();
            releasedReservations.add(hash(request.reservationId()));
        }

        @Override
        public SeckillResult trySeckillDeduct(Long skuId, long quantity) {
            Long remaining = seckillStock.get(skuId);
            if (remaining == null) {
                return new SeckillResult(true, -2, true); // 未播种秒杀配额 → 普通品放行（bypass，不回滚）
            }
            if (remaining < quantity) {
                return new SeckillResult(false, -1, false);
            }
            seckillStock.put(skuId, remaining - quantity);
            return new SeckillResult(true, remaining - quantity, false);
        }

        @Override
        public void rollbackSeckill(Long skuId, long quantity) {
            Long remaining = seckillStock.get(skuId);
            if (remaining != null) {
                seckillStock.put(skuId, remaining + quantity);
            }
        }

        private long hash(String id) {
            return id.hashCode();
        }
    }

    @Test
    void createOrderReservesStockAndCreatesOneToOneTransactionNoPaymentIntent() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 50);
        OrderApplicationService service = service(client);

        CreateOrderResult result = service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-1");

        assertThat(result.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.totalMinor()).isEqualTo(200L);
        assertThat(result.orderNo()).isNotNull();
        // Feature 015：下单不再同步创建支付单，用户显式选渠道时才建
        assertThat(paymentGateway.requests).isEmpty();

        // 预占扣减：50 - 2 = 48
        assertThat(client.available(1L)).isEqualTo(48L);
    }

    @Test
    void paymentSuccessConfirmsStock() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 50);
        OrderApplicationService service = service(client);

        CreateOrderResult result = service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-1");
        assertThat(client.available(1L)).isEqualTo(48L);

        service.onPaymentSucceeded(PaymentSucceededRequest.withoutItems("PM-1", result.orderNo(),
                result.transactionNo(), "u1", 200L, "CNY"));

        // 确认扣减后 reserved 归零、sold 增加
        assertThat(client.available(1L)).isEqualTo(48L);
        assertThat(client.sold(1L)).isEqualTo(2L);
    }

    @Test
    void insufficientStockFailsOrderAndReleasesReservation() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 1); // 仅 1 件
        OrderApplicationService service = service(client);

        assertThatThrownBy(() -> service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));

        // 预占失败回滚：库存恢复为 1
        assertThat(client.available(1L)).isEqualTo(1L);
        assertThat(paymentGateway.requests).isEmpty();
    }

    @Test
    void nonSellableSkuRejected() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", false), 50);
        OrderApplicationService service = service(client);

        assertThatThrownBy(() -> service.createOrder("u1", "m1", List.of(new OrderLine(1L, 1)), "idk-2"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        assertThat(paymentGateway.requests).isEmpty();
    }

    @Test
    void unknownSkuRejected() {
        FakeCatalogClient client = new FakeCatalogClient();
        OrderApplicationService service = service(client);

        assertThatThrownBy(() -> service.createOrder("u1", "m1", List.of(new OrderLine(999L, 1)), "idk-3"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND));
        assertThat(paymentGateway.requests).isEmpty();
    }

    @Test
    void seckillDeniesWhenQuotaExhausted() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 50);
        client.seedSeckill(1L, 1); // 仅 1 件秒杀配额
        OrderApplicationService service = service(client);

        assertThatThrownBy(() -> service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)), "idk-seckill"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        // 秒杀预扣失败回滚：库存与支付意图均不应产生
        assertThat(client.available(1L)).isEqualTo(50L);
        assertThat(paymentGateway.requests).isEmpty();
    }
}
