package com.payment.payment.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.channelgateway.api.dto.ChannelCallbackRequest;
import com.payment.channelgateway.application.ChannelCallbackHandler;
import com.payment.channelgateway.application.ChannelRegistry;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.infra.AlipayChannelAdapter;
import com.payment.channelgateway.infra.alipay.AlipayGateway;
import com.payment.channelgateway.support.StubChannelRegistry;
import com.payment.payment.domain.Payment;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.channelgateway.infra.persistence.InMemoryChannelOrderRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 030 / T125 / SC-B2-06：JSON 回调路径与支付宝 notify 路径的<b>收敛行为一致</b>。
 *
 * <p>两条入站路径形态迥异——
 * <ul>
 *   <li><b>JSON 路径</b>：{@code POST /internal/payments/{no}/channel-callback}，
 *       验签在 {@code ChannelCallbackSignatureFilter}（过滤器层，未过则不触达 Controller）；
 *       报文是结构化 JSON，映射由 {@link ChannelCallbackRequest#toResult()} 完成；</li>
 *   <li><b>notify 路径</b>：{@code POST /internal/channels/ALIPAY/callback}，
 *       验签在支付宝插件内（{@code ChannelPlugin#parseCallback} 四步第一步）；
 *       报文是表单，映射由插件的 {@code parseCallback} 完成。</li>
 * </ul>
 *
 * <p><b>T6 迁移说明</b>：notify 路径原先走支付宝专属端点 {@code AlipayNotifyController}
 * （FR-015 已删除），现走<b>通用端点</b>{@code ChannelPluginCallbackController} →
 * {@code ChannelCallbackHandler} 四步模板 → {@link PaymentNotifyPort}。
 * 本类因此装配的不再是那个专属 Controller，而是通用端点背后的真实链路——
 * <b>断言一条未改</b>：SC-B2-06 要锁的是「两条路收敛到同一处」，
 * 而这件事在迁移前后是同一个事实，只是入口换了名字。</p>
 *
 * <p><b>三条必须一致的不变量</b>（这正是 SC-B2-06 怕的东西：两条路各自演化后语义分叉）：
 * <ol>
 *   <li><b>同状态语义</b>：{@code TRADE_SUCCESS} 与 JSON {@code SUCCESS} 都必须收敛 SUCCEEDED；
 *       {@code TRADE_CLOSED} 与 {@code FAILURE} 都必须 FAILED；无结论态都必须 UNKNOWN 不推进；</li>
 *   <li><b>终态吸收一致</b>：两条路径都经同一 {@link PaymentCallbackService#handleCallback}，
 *       重复/迟到回调的幂等吸收必须同口径；</li>
 *   <li><b>拒绝不留半写一致</b>：任一路径判定「不可信」时，状态一个字节都不许动。</li>
 * </ol>
 * 本类<b>不</b>断言两条路径的报文校验规则相同（金额/币种是 notify 独有的语义校验，
 * 层位与职责本就不同）——那是刻意的差异，不是分叉。
 */
class PaymentCallbackPathParityTest {

    private static final String PAYMENT_NO = "PM-PARITY-1";

    /** 沙箱 app_id：插件据此做身份一致性校验（FR-203），报文必须带同一个值。 */
    private static final String APP_ID = "sandbox-app-1";

    private PaymentTestStack stack;
    private InMemoryPaymentRepository payments;
    private InMemoryChannelOrderRepository attempts;
    private ChannelCallbackHandler handler;

    /** 网关桩：验签恒通过（本类只比收敛语义，不比验签）。 */
    private static final class StubGateway implements AlipayGateway {
        @Override
        public PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                                     String subject, String notifyUrl, String returnUrl, Instant expireAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueryResult query(String outTradeNo, String channelTransactionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult refund(String outTradeNo, String channelTransactionId, String refundNo,
                                   long amountMinor, String currency, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean verifyNotify(Map<String, String> rawParams) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        stack = new PaymentTestStack();
        payments = stack.payments;
        attempts = stack.attempts;
        resetPayment();

        // notify 路径的真实链路（T6 迁移后它的唯一入口）：
        // ALIPAY 插件（验签桩恒通过）→ 网关域四步模板 → Payment 入向端口 → 既有收敛链路
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, new StubGateway(), true, APP_ID);
        ChannelRegistry registry = new StubChannelRegistry().register(AlipayChannelAdapter.CODE, adapter);
        PaymentNotifyPort port = new DefaultPaymentNotifyPort(stack.callback, null,
                payments, attempts, new NoopBusinessMetrics(), new StructuredAuditLogger());
        handler = new ChannelCallbackHandler(registry, port, new NoopBusinessMetrics());
    }

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    /** 每段测试前重置成同一张 PROCESSING 单（两条路径从同一状态出发才谈得上可比）。 */
    private void resetPayment() {
        payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));
        attempts.save(ChannelOrder.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, ChannelOrderStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(ChannelOrder.CHANNEL_MODE_KEY, "SANDBOX")));
    }

    private PaymentStatus status() {
        return payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus();
    }

    /** JSON 路径：模拟 Controller 的入站映射（request.toResult() + handleCallback）。 */
    private void viaJsonPath(ChannelCallbackRequest request) {
        stack.callback.handleCallback(PAYMENT_NO, request.toResult());
    }

    /**
     * notify 路径：支付宝表单报文经<b>通用端点</b>全链路。
     *
     * <p>与旧专属端点的对应关系：验签（插件 parseCallback ①）、身份（②）、
     * 报文翻译（④）合并在插件里一次完成；业务校验（引用/金额/币种）在
     * {@code DefaultPaymentNotifyPort}；收敛仍在同一 {@code handleCallback}。</p>
     */
    private void viaNotifyPath(String tradeStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", tradeStatus);
        params.put("trade_no", "ch-1");
        params.put("total_amount", "10.00");
        params.put("app_id", APP_ID);
        handler.handle(AlipayChannelAdapter.CODE, ChannelCallbackEnvelope.form(Map.of(), params));
    }

    // ---- ① 成功语义一致 ----

    @Test
    @DisplayName("成功：JSON SUCCESS 与 notify TRADE_SUCCESS 都收敛 SUCCEEDED [SC-B2-06]")
    void successSemanticsMatch() {
        viaJsonPath(new ChannelCallbackRequest("SUCCESS", "ch-1", null, 10_00L));
        assertThat(status()).as("JSON 路径").isEqualTo(PaymentStatus.SUCCEEDED);

        resetPayment();
        viaNotifyPath("TRADE_SUCCESS");
        assertThat(status()).as("notify 路径").isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("成功：TRADE_FINISHED 与 TRADE_SUCCESS 同口径（已完结也是成功）[FR-204]")
    void tradeFinishedMatchesTradeSuccess() {
        viaNotifyPath("TRADE_FINISHED");
        assertThat(status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- ② 失败语义一致 ----

    @Test
    @DisplayName("失败：JSON FAILURE 与 notify TRADE_CLOSED 都收敛 FAILED [SC-B2-06]")
    void failureSemanticsMatch() {
        viaJsonPath(new ChannelCallbackRequest("FAILURE", "ch-1", "channel declined", 10_00L));
        assertThat(status()).as("JSON 路径").isEqualTo(PaymentStatus.FAILED);

        resetPayment();
        viaNotifyPath("TRADE_CLOSED");
        assertThat(status()).as("notify 路径").isEqualTo(PaymentStatus.FAILED);
    }

    // ---- ③ 无结论语义一致（不推进） ----

    @Test
    @DisplayName("无结论：JSON UNKNOWN 与 notify WAIT_BUYER_PAY 都**不落终态**，收敛 UNKNOWN [SC-B2-06]")
    void unknownSemanticsMatch() {
        viaJsonPath(new ChannelCallbackRequest("UNKNOWN", "ch-1", "no conclusion", null));
        assertThat(status()).as("JSON 路径").isEqualTo(PaymentStatus.UNKNOWN);

        resetPayment();
        viaNotifyPath("WAIT_BUYER_PAY");
        assertThat(status()).as("notify 路径").isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("无结论：两条路径都绝不把 UNKNOWN 当 SUCCEEDED 或 FAILED [FR-250~FR-252]")
    void unknownNeverBecomesTerminalOnEitherPath() {
        viaNotifyPath("WAIT_BUYER_PAY");
        assertThat(status()).isNotIn(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED);

        resetPayment();
        viaJsonPath(new ChannelCallbackRequest("UNKNOWN", null, "timeout", null));
        assertThat(status()).isNotIn(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED);
    }

    // ---- ④ 幂等吸收一致 ----

    @Test
    @DisplayName("幂等：两条路径的重复回调都被终态吸收，状态稳定 [SC-B2-06]")
    void duplicateAbsorptionMatches() {
        viaJsonPath(new ChannelCallbackRequest("SUCCESS", "ch-1", null, 10_00L));
        viaJsonPath(new ChannelCallbackRequest("SUCCESS", "ch-1", null, 10_00L));
        assertThat(status()).as("JSON 路径重复").isEqualTo(PaymentStatus.SUCCEEDED);

        resetPayment();
        viaNotifyPath("TRADE_SUCCESS");
        viaNotifyPath("TRADE_SUCCESS");
        assertThat(status()).as("notify 路径重复").isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("幂等：成功后迟到的失败回调**两条路径都**被吸收，不翻盘 [SC-B2-06]")
    void lateFailureAbsorbedOnBothPaths() {
        viaJsonPath(new ChannelCallbackRequest("SUCCESS", "ch-1", null, 10_00L));
        viaJsonPath(new ChannelCallbackRequest("FAILURE", "ch-1", "late failure", 10_00L));
        assertThat(status()).as("JSON 路径：成功后迟到失败不翻盘").isEqualTo(PaymentStatus.SUCCEEDED);

        resetPayment();
        viaNotifyPath("TRADE_SUCCESS");
        viaNotifyPath("TRADE_CLOSED");
        assertThat(status()).as("notify 路径：成功后迟到关闭不翻盘").isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("幂等：attempt 终态在两条路径下同样被吸收（不重复迁移）[SC-B2-06]")
    void attemptTerminalAbsorptionMatches() {
        viaJsonPath(new ChannelCallbackRequest("SUCCESS", "ch-1", null, 10_00L));
        ChannelOrderStatus afterJson = attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus();
        assertThat(afterJson).isEqualTo(ChannelOrderStatus.SUCCEEDED);

        resetPayment();
        viaNotifyPath("TRADE_SUCCESS");
        ChannelOrderStatus afterNotify = attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus();
        assertThat(afterNotify)
                .as("两条路径的 attempt 终态必须一致")
                .isEqualTo(afterJson);
    }

    // ---- ⑤ 拒绝不留半写一致 ----

    @Test
    @DisplayName("拒绝：notify 金额不符时状态零改动（与 JSON 路径的「校验失败零改动」同纪律）[FR-213]")
    void notifyRejectionLeavesNoPartialWriteLikeJsonPath() {
        Payment before = payments.findByPaymentNo(PAYMENT_NO).orElseThrow();
        Integer versionBefore = before.getVersion();
        PaymentStatus statusBefore = before.getStatus();

        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("trade_no", "ch-1");
        params.put("total_amount", "99.99"); // 应付款 10.00
        params.put("app_id", APP_ID);
        handler.handle(AlipayChannelAdapter.CODE, ChannelCallbackEnvelope.form(Map.of(), params));

        Payment after = payments.findByPaymentNo(PAYMENT_NO).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(statusBefore);
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                .isEqualTo(ChannelOrderStatus.ACCEPTED);
    }

    // ---- ⑥ 两路径共享同一收敛链路（结构证据） ----

    @Test
    @DisplayName("两条路径共用同一 handleCallback：ChannelResult 语义决定结果，而非路径来源 [FR-205]")
    void resultSemanticsDecideOutcomeNotThePath() {
        // 同一 ChannelResult 直接喂给共享的收敛服务，结果与经 notify 映射出来的完全一致
        stack.callback.handleCallback(PAYMENT_NO, ChannelResult.success("ch-1"));
        PaymentStatus direct = status();

        resetPayment();
        viaNotifyPath("TRADE_SUCCESS");
        assertThat(status())
                .as("notify 映射出的 success 与直接构造的 success 必须收敛到同一状态")
                .isEqualTo(direct);
    }
}
