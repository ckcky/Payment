package com.payment.payment.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.channelgateway.api.AlipayNotifyController;
import com.payment.channelgateway.api.dto.ChannelCallbackRequest;
import com.payment.channelgateway.infra.alipay.AlipayGateway;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.channelgateway.infra.config.AlipaySandboxProperties;
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
 *   <li><b>notify 路径</b>：{@code POST /internal/channels/alipay/notify}，
 *       验签在 {@link AlipayNotifyController} 内（三段式第一步）；报文是表单，
 *       映射由 controller 的 {@code toChannelResult} 完成。</li>
 * </ul>
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

    private PaymentTestStack stack;
    private InMemoryPaymentRepository payments;
    private InMemoryPaymentAttemptRepository attempts;
    private AlipayNotifyController notifyController;

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

        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId("sandbox-app-1");
        notifyController = new AlipayNotifyController(new StubGateway(), properties, stack.callback,
                payments, attempts, new com.payment.common.core.observability.NoopBusinessMetrics(),
                new com.payment.common.core.observability.StructuredAuditLogger());
    }

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    /** 每段测试前重置成同一张 PROCESSING 单（两条路径从同一状态出发才谈得上可比）。 */
    private void resetPayment() {
        payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));
        attempts.save(PaymentAttempt.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX")));
    }

    private PaymentStatus status() {
        return payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus();
    }

    /** JSON 路径：模拟 Controller 的入站映射（request.toResult() + handleCallback）。 */
    private void viaJsonPath(ChannelCallbackRequest request) {
        stack.callback.handleCallback(PAYMENT_NO, request.toResult());
    }

    /** notify 路径：表单报文经 controller 全链路。 */
    private void viaNotifyPath(String tradeStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", tradeStatus);
        params.put("trade_no", "ch-1");
        params.put("total_amount", "10.00");
        params.put("app_id", "sandbox-app-1");
        notifyController.onNotify(params);
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
        PaymentAttemptStatus afterJson = attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus();
        assertThat(afterJson).isEqualTo(PaymentAttemptStatus.SUCCEEDED);

        resetPayment();
        viaNotifyPath("TRADE_SUCCESS");
        PaymentAttemptStatus afterNotify = attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus();
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
        params.put("app_id", "sandbox-app-1");
        notifyController.onNotify(params);

        Payment after = payments.findByPaymentNo(PAYMENT_NO).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(statusBefore);
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.ACCEPTED);
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
