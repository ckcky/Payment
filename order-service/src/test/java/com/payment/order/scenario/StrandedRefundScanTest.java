package com.payment.order.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.order.application.FulfillmentGateway;
import com.payment.order.application.MqTestSupport;
import com.payment.order.application.NoopTransactionManager;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.OrderTimeoutScheduler;
import com.payment.order.application.PaymentGateway;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.application.StrandedRefundOrderScanner;
import com.payment.order.application.TransactionApplicationService;
import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.RefundOrderStatus;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRefundRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * TT-7（spec 034 plan §6 / §7.3 F-5，order L1）：搁浅 TXRF 扫描器的<b>有界重放</b>与
 * <b>耗尽显性化</b>。
 *
 * <p>搁浅形态：首调 payment 失败 ⇒ TXRF 卡 {@code REQUESTED} 且 {@code paymentRefundNo=null}，
 * 无人再发起。{@link StrandedRefundOrderScanner} 补上「发起」这一环——重放
 * {@code retryStrandedRefund}（同号复用，不新建第二张 TXRF）。</p>
 *
 * <p>本类钉死的三条性质（spec §7.3 红线）：</p>
 * <ol>
 *   <li><b>有界</b>：每单累计重放 ≤2 次（渠道请求次数 = 首调 1 + 重放 2，恒不发散）；</li>
 *   <li><b>耗尽显性化</b>：达上限即登记 {@code FINANCIAL_AUDIT} 审计（order.refund_stranded_replay_exhausted）
 *       且停扫该单（第三轮不再发起渠道调用）；</li>
 *   <li><b>R-3</b>：扫描器绝不自行判定退款成败——重放全败后 TXRF 仍 {@code REQUESTED}
 *       （结论恒由 payment 权威回调写入），未搁浅/已受理的单不被触碰。</li>
 * </ol>
 *
 * <p>回拨时间戳经 {@code rehydrate} 注入（InMemory 仓储对已有 updated_at 不覆盖——
 * 与 DB 列「已有值不洗掉」语义对齐，见 {@code InMemoryTransactionRefundRepository#save}）。</p>
 */
class StrandedRefundScanTest {

    private static final Duration STRANDED_THRESHOLD = Duration.ofSeconds(30);

    private final InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final InMemoryTransactionRefundRepository refundRepository = new InMemoryTransactionRefundRepository();
    private final StubPaymentGateway paymentGateway = new StubPaymentGateway();
    private final RecordingFulfillmentGateway fulfillmentGateway = new RecordingFulfillmentGateway();
    private final RecordingAuditLogger auditLogger = new RecordingAuditLogger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private OrderApplicationService orderLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new OrderApplicationService(orderRepository, transactionRepository, client, paymentGateway,
                new com.payment.common.core.observability.NoopBusinessMetrics(),
                Mockito.mock(OrderTimeoutScheduler.class), fulfillmentGateway,
                new NoopTransactionManager(), MqTestSupport.off());
    }

    private TransactionApplicationService transactionLayer(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        return new TransactionApplicationService(orderRepository, transactionRepository, refundRepository,
                orderLayer(client), paymentGateway, fulfillmentGateway, client,
                new com.payment.common.core.observability.NoopBusinessMetrics(), auditLogger,
                MqTestSupport.off(), new NoopTransactionManager());
    }

    private StrandedRefundOrderScanner scanner(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        BusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
        return new StrandedRefundOrderScanner(refundRepository, transactionLayer(client),
                auditLogger, metrics, STRANDED_THRESHOLD);
    }

    private String paidOrder(SuccessfulPurchaseScenarioTest.FakeCatalogClient client) {
        OrderApplicationService orders = orderLayer(client);
        String orderNo = orders.createOrder("u1", "m1",
                List.of(new OrderLine(1L, 2)), "idk-tt7").orderNo();
        transactionLayer(client).onPaymentSucceeded(succeeded(orderNo, "PM-TT7"));
        return orderNo;
    }

    private static PaymentSucceededRequest succeeded(String orderNo, String paymentNo) {
        return PaymentSucceededRequest.withoutItems(paymentNo, orderNo, "txn-tt7", "u1", 200L, "CNY");
    }

    private SuccessfulPurchaseScenarioTest.FakeCatalogClient clientWithSku() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = new SuccessfulPurchaseScenarioTest.FakeCatalogClient();
        client.seedSku(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true), 50);
        return client;
    }

    /** 把 TXRF 的 updated_at 回拨到 threshold 之前（TT-7 口径：rehydrate 注入，仓储不洗掉）。 */
    private void backdate(RefundOrder txrf, Instant persistedAt) {
        RefundOrder aged = RefundOrder.rehydrate(txrf.getId(), txrf.getRefundNo(), txrf.getPaymentRefundNo(),
                txrf.getTransactionNo(), txrf.getOrderNo(), txrf.getPaymentNo(), txrf.getUserId(),
                txrf.getAmountMinor(), txrf.getCurrencyCode(), txrf.getStatus(), txrf.getReason(),
                txrf.getVersion(), persistedAt);
        refundRepository.save(aged);
    }

    @Test
    void strandedTxrfIsReplayedOnceAndConvergesByChannelTruth() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        paymentGateway.failFirstNCalls = 1; // 首调失败 ⇒ 搁浅
        assertThatThrownBy(() -> service.createRefund(orderNo, null, 100L, "MANUAL"))
                .isInstanceOf(IllegalStateException.class);
        RefundOrder stranded = refundRepository.findByOrderNo(orderNo).get(0);
        assertThat(stranded.getStatus()).isEqualTo(RefundOrderStatus.REQUESTED);
        assertThat(stranded.getPaymentRefundNo()).isNull();

        // 未达搁浅阈值：不触发重放（阈值守卫）
        int beforeThreshold = scanner(client).scanRound(Instant.now());
        assertThat(beforeThreshold).isZero();
        assertThat(paymentGateway.refundRequests).hasSize(1);

        // 回拨 updated_at 超 30s ⇒ 扫描器重放，同号复用、渠道再调一次，本次受理成功
        backdate(stranded, Instant.now().minusSeconds(60));
        int replayed = scanner(client).scanRound(Instant.now());

        assertThat(replayed).isEqualTo(1);
        assertThat(paymentGateway.refundRequests).hasSize(2); // 首调失败 + 一次重放
        RefundOrder converged = refundRepository.findByOrderNo(orderNo).get(0);
        assertThat(converged.getRefundNo()).isEqualTo(stranded.getRefundNo()); // 同号复用，无第二张 TXRF
        assertThat(converged.getStatus()).isEqualTo(RefundOrderStatus.PROCESSING);
        assertThat(converged.getPaymentRefundNo()).startsWith("PMRF");
        // 结论由渠道受理写入，而非扫描器判定；无耗尽审计登记
        assertThat(auditLogger.exhaustedActions()).isEmpty();
        assertThat(registry.get("order.refund_stranded_replayed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void exhaustedReplayIsBoundedAtTwoAndAuditedOnce() {
        SuccessfulPurchaseScenarioTest.FakeCatalogClient client = clientWithSku();
        TransactionApplicationService service = transactionLayer(client);
        String orderNo = paidOrder(client);

        paymentGateway.failFirstNCalls = 99; // 首调 + 全部重放持续失败
        assertThatThrownBy(() -> service.createRefund(orderNo, null, 100L, "MANUAL"))
                .isInstanceOf(IllegalStateException.class);
        RefundOrder stranded = refundRepository.findByOrderNo(orderNo).get(0);
        backdate(stranded, Instant.now().minusSeconds(60));

        StrandedRefundOrderScanner strandedScanner = scanner(client);
        strandedScanner.scanRound(Instant.now()); // 重放 1/2，失败
        strandedScanner.scanRound(Instant.now()); // 重放 2/2，失败 ⇒ 耗尽审计
        strandedScanner.scanRound(Instant.now()); // 达上限：停扫，不再发起渠道调用

        // 有界：首调 1 次 + 重放 2 次 = 3，第三轮零新增渠道调用（≤2 红线）
        assertThat(paymentGateway.refundRequests).hasSize(3);
        // 耗尽显性化：FINANCIAL_AUDIT 审计恰一条（审计器与交易层共用，过滤本 action）+ 指标
        assertThat(auditLogger.exhaustedActions()).containsExactly("order.refund_stranded_replay_exhausted");
        assertThat(registry.get("order.refund_stranded_replay_exhausted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("order.refund_stranded_replay_failed").counter().count()).isEqualTo(2.0);
        // R-3：扫描器不判定成败——重放全败后仍 REQUESTED，等 payment 权威回调 / 人工收敛
        RefundOrder stuck = refundRepository.findByOrderNo(orderNo).get(0);
        assertThat(stuck.getStatus()).isEqualTo(RefundOrderStatus.REQUESTED);
        assertThat(stuck.getPaymentRefundNo()).isNull();
    }

    /** 记录型审计器：捕获 FINANCIAL_AUDIT 语义的 action 序列（替代只写日志的基类）。 */
    private static final class RecordingAuditLogger extends StructuredAuditLogger {
        final List<String> actions = new ArrayList<>();

        @Override
        public void audit(String action, String idempotencyKey, Long amountMinor, String currencyCode,
                          String fromStatus, String toStatus, String module, String detail) {
            actions.add(action);
        }

        /** 扫描器耗尽登记（与交易层业务审计共用实例，按 action 过滤）。 */
        List<String> exhaustedActions() {
            return actions.stream()
                    .filter("order.refund_stranded_replay_exhausted"::equals)
                    .toList();
        }
    }

    /** 按序失败的 payment 桩：前 N 次 refund 抛异常（对齐 TransactionRefundTest 同名桩）。 */
    private static final class StubPaymentGateway implements PaymentGateway {
        final List<RefundCommandRequest> refundRequests = new ArrayList<>();
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

    /** 记录型履约网关（对齐既有测试桩）。 */
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
