package com.payment.payment.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.channelgateway.application.ChannelCallbackHandler;
import com.payment.channelgateway.application.ChannelRegistry;
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
import com.payment.payment.support.RecordingObservability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 030 / T124 / SC-B2-01~04：notify 校验失败的<b>三件套</b>（FR-213）。
 *
 * <p>{@code PaymentNotifyPortTest} 覆盖了「金额/币种/引用不符 ⇒ 拒绝 + 三件套」在
 * <b>端口层</b>的口径；本类从<b>入站端点</b>出发把同一条纪律再走一遍——因为端点这一层
 * 还多了一段「插件验签/身份 → 网关模板 → 端口」的路，而<b>那段路上的失败也必须是拒绝</b>，
 * 不能因为「还没到业务校验」就变成静默通过。</p>
 *
 * <p><b>T6 迁移说明</b>：本类原先驱动的是支付宝专属端点 {@code AlipayNotifyController}
 * （FR-015 已删除）。现驱动通用端点背后的真实链路
 * （{@code ChannelCallbackHandler} + {@link DefaultPaymentNotifyPort}），
 * 报文形态、拒绝语义、三件套断言全部不变。</p>
 *
 * <p>为什么三件套要一起断言：静默拒绝是最危险的形态——状态没动看起来「一切正常」，
 * 差异被吞进黑洞，直到对账日才暴露。指标负责「有异常」，审计负责「是哪一笔」，
 * 缺任何一个都无法在事故中定位。</p>
 *
 * <h3>一处刻意的断言变更（已获放行）</h3>
 * <p>{@link ReferenceMismatch#appIdMismatchRejects()} 原先断言 {@code reason=app_id}。
 * 迁移后 {@code app_id} 校验归属「①签名/身份」段（由插件承担，与验签同源），
 * 故归入 {@code reason=signature}——<b>该监控维度随之消失</b>，这是 T6 前已上报
 * 并经裁决放行的唯一一处断言变更。其余断言一条未改。</p>
 */
class PaymentCallbackValidationTest {

    private static final String PAYMENT_NO = "PM-VAL-1";

    /** 沙箱 app_id：插件据此做身份一致性校验（FR-203），报文必须带同一个值。 */
    private static final String APP_ID = "sandbox-app-1";

    private PaymentTestStack stack;
    private InMemoryPaymentRepository payments;
    private InMemoryChannelOrderRepository attempts;
    private RecordingObservability obs;
    private StubGateway gateway;
    private ChannelCallbackHandler handler;

    /** 网关桩：验签恒通过，专注业务侧校验；pagePay/query/refund 不走。 */
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
            return true; // 本类专注业务侧校验，验签恒定通过
        }
    }

    @BeforeEach
    void setUp() {
        stack = new PaymentTestStack();
        payments = stack.payments;
        attempts = stack.attempts;
        obs = new RecordingObservability();

        payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));
        attempts.save(ChannelOrder.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, ChannelOrderStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(ChannelOrder.CHANNEL_MODE_KEY, "SANDBOX")));

        gateway = new StubGateway();

        // 用记录式观测装配真实收敛服务：这样指标/审计都进内存，可断言。
        // 指标同时交给网关模板（签名段的拒绝由它计）与入向端口（业务段的拒绝由它计）——
        // 两段共用一个记录器，才能证明「无论在哪一段被拒，都留了痕迹」。
        PaymentResultProcessor processor = new PaymentResultProcessor(payments, attempts, stack.order);
        PaymentCallbackService callback = new PaymentCallbackService(processor, payments, obs.metrics, obs.audit);
        PaymentNotifyPort port = new DefaultPaymentNotifyPort(callback, null,
                payments, attempts, obs.metrics, obs.audit);

        ChannelRegistry registry = new StubChannelRegistry().register(AlipayChannelAdapter.CODE,
                new AlipayChannelAdapter(AlipayChannelAdapter.Scenario.SUCCESS, gateway, true, APP_ID));
        handler = new ChannelCallbackHandler(registry, port, obs.metrics);
    }

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    private static Map<String, String> notifyParams(String totalAmount, String tradeNo, String status) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", status);
        params.put("trade_no", tradeNo);
        if (totalAmount != null) {
            params.put("total_amount", totalAmount);
        }
        params.put("app_id", APP_ID);
        params.put("sign", "fake");
        params.put("sign_type", "RSA2");
        return params;
    }

    /** 走通用端点：支付宝表单报文 → 插件翻译 → 网关四步模板 → 入向端口，取应答体。 */
    private String notifyBody(Map<String, String> params) {
        return handler.handle(AlipayChannelAdapter.CODE,
                ChannelCallbackEnvelope.form(Map.of(), params)).body();
    }

    /** 三件套的统一断言：状态不动 + 该 reason 的指标 + 审计留痕。 */
    private void assertRejectedTriple(String expectedReasonTag) {
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("① 不推进：状态与 version 都不得变")
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                .as("① 不推进：attempt 也不得被推进")
                .isEqualTo(ChannelOrderStatus.ACCEPTED);

        assertThat(obs.countOf("payment.notify_rejected", "reason", expectedReasonTag))
                .as("② 计指标：reason=%s 的拒绝计数必须 +1", expectedReasonTag)
                .isEqualTo(1);
        assertThat(obs.auditedAction("payment.notify_rejected"))
                .as("③ 写审计：资金面分歧必须留 FINANCIAL_AUDIT 痕迹")
                .isTrue();
    }

    // ---- ① 金额不符（SC-B2-01 / CB-5） ----

    @Nested
    @DisplayName("① 金额不符（FR-210 / SC-B2-01）")
    class AmountMismatch {

        @Test
        @DisplayName("金额少付 ⇒ 三件套齐全，不推进 [SC-B2-01]")
        void underpaidRejects() {
            assertThat(notifyBody(notifyParams("9.99", "ch-1", "TRADE_SUCCESS")))
                    .contains("rejected");
            assertRejectedTriple("amount");
        }

        @Test
        @DisplayName("金额多付 ⇒ 同样拒绝（多付也是分歧，不能静默接受）[SC-B2-01]")
        void overpaidRejects() {
            assertThat(notifyBody(notifyParams("10.01", "ch-1", "TRADE_SUCCESS")))
                    .contains("rejected");
            assertRejectedTriple("amount");
        }

        @Test
        @DisplayName("金额恰好一致 ⇒ 收敛成功且**无**拒绝痕迹（诚实拒绝的反面）")
        void exactAmountLeavesNoRejectionTrace() {
            assertThat(notifyBody(notifyParams("10.00", "ch-1", "TRADE_SUCCESS")))
                    .isEqualTo("success");

            assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(obs.countOf("payment.notify_rejected"))
                    .as("校验通过绝不能留下拒绝痕迹，否则指标永久高位误导排障")
                    .isZero();
        }

        @Test
        @DisplayName("金额缺失 ⇒ 跳过校验，不产生拒绝（向后兼容既有 mock 回调形态）")
        void missingAmountSkipsValidation() {
            assertThat(notifyBody(notifyParams(null, "ch-1", "TRADE_SUCCESS")))
                    .isEqualTo("success");
            assertThat(obs.countOf("payment.notify_rejected")).isZero();
        }
    }

    // ---- ② 币种不符（SC-B2-02 / CB-6） ----

    @Nested
    @DisplayName("② 币种不符（FR-211 / SC-B2-02）")
    class CurrencyMismatch {

        @Test
        @DisplayName("平台币种非 CNY 而通知带金额 ⇒ 三件套齐全，不推进 [SC-B2-02]")
        void nonCnyPlatformRejectsWhenAmountPresent() {
            // 覆盖成一张 USD 单：支付宝境内通知恒按 CNY 语义，故判币种不一致
            payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                    10_00L, "USD", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));

            assertThat(notifyBody(notifyParams("10.00", "ch-1", "TRADE_SUCCESS")))
                    .contains("rejected");
            assertRejectedTriple("currency");
        }

        @Test
        @DisplayName("币种校验失败**优先于**收敛：绝不因币种问题仍落成功 [SC-B2-02]")
        void currencyRejectPreventsConvergence() {
            payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                    10_00L, "USD", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));

            notifyBody(notifyParams("10.00", "ch-1", "TRADE_SUCCESS"));

            assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                    .isNotEqualTo(PaymentStatus.SUCCEEDED);
        }
    }

    // ---- ③ 引用归属不符（SC-B2-03/04 / CB-7/CB-8） ----

    @Nested
    @DisplayName("③ 引用归属不符（FR-212 / SC-B2-03~04）")
    class ReferenceMismatch {

        @Test
        @DisplayName("本单已记录引用而通知携带另一个 ⇒ 三件套齐全，不推进（防渠道重发他笔）[SC-B2-03]")
        void conflictingReferenceRejects() {
            ChannelOrder attempt = attempts.findByPaymentNo(PAYMENT_NO).get(0);
            attempt.backfillChannelReference("ch-original");
            attempts.save(attempt);

            assertThat(notifyBody(notifyParams("10.00", "ch-DIFFERENT", "TRADE_SUCCESS")))
                    .contains("rejected");
            assertRejectedTriple("channel_reference");
        }

        @Test
        @DisplayName("引用一致 ⇒ 正常收敛，不留拒绝痕迹 [SC-B2-04]")
        void matchingReferenceConverges() {
            ChannelOrder attempt = attempts.findByPaymentNo(PAYMENT_NO).get(0);
            attempt.backfillChannelReference("ch-1");
            attempts.save(attempt);

            assertThat(notifyBody(notifyParams("10.00", "ch-1", "TRADE_SUCCESS")))
                    .isEqualTo("success");
            assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(obs.countOf("payment.notify_rejected")).isZero();
        }

        @Test
        @DisplayName("受理时引用为空 ⇒ 首次通知正是回填时机，不算不一致（不误拒）")
        void nullRecordedReferenceIsBackfillOpportunity() {
            // setUp 里 attempt 的 channelReference 本就是 null
            assertThat(notifyBody(notifyParams("10.00", "ch-first", "TRADE_SUCCESS")))
                    .isEqualTo("success");
            assertThat(obs.countOf("payment.notify_rejected")).isZero();
        }

        /**
         * <b>唯一获放行的断言变更</b>（T6 前上报、经裁决执行）。
         *
         * <p>迁移前 {@code app_id} 校验住在支付宝专属端点的 {@code validate()} 里，
         * 与渠道引用/金额币种同列，故有独立的 {@code reason=app_id} 维度。迁移后它随
         * 「①签名/身份」段一起下沉到插件 {@code parseCallback} 的第②步——与验签<b>同源</b>
         * （都是「这条通知是不是发给我们的」），失败因此归入 {@code reason=signature}。</p>
         *
         * <p><b>维度消失是有意的</b>：把身份校验混进业务校验会让「报文不可信」与
         * 「报文可信但与我们记的对不上」这两类完全不同的事故共用一个监控面。
         * 代价是失去 {@code app_id} 这一维度的独立可见性——已登记。</p>
         *
         * <p><b>另一条纪律在这里显形</b>：身份失败发生在①段，报文<b>未过门</b>，
         * 因此不触达 Payment 侧、也<b>不写业务审计</b>（INV-10）。这与业务段的拒绝
         * （必写审计）是刻意的差别，不是遗漏。</p>
         */
        @Test
        @DisplayName("app_id 不符 ⇒ 在①签名/身份段被拒，归入 reason=signature 且**不触达** Payment 侧 [FR-203][INV-10]")
        void appIdMismatchRejects() {
            Map<String, String> params = notifyParams("10.00", "ch-1", "TRADE_SUCCESS");
            params.put("app_id", "someone-elses");

            assertThat(notifyBody(params)).contains("rejected");

            // ① 不推进：状态与 attempt 一个字节都不许动
            assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.PROCESSING);
            assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                    .isEqualTo(ChannelOrderStatus.ACCEPTED);

            // ② 计指标：身份校验与验签同源，归入 signature 维度（app_id 维度已按裁决取消）
            assertThat(obs.countOf("payment.notify_rejected", "reason", "signature")).isEqualTo(1);

            // ③ 不写业务审计：①段失败即报文未过门，Payment 侧从未被触达（INV-10）
            assertThat(obs.auditedAction("payment.notify_rejected"))
                    .as("①签名/身份段失败不得触达 Payment 侧，故不留业务审计痕迹")
                    .isFalse();
        }
    }

    // ---- ④ 审计字段正确性（FR-293） ----

    @Test
    @DisplayName("拒绝审计携带 paymentNo、币种与 REJECTED 终态（可反查到具体哪一笔）[FR-293]")
    void rejectionAuditCarriesPaymentIdentity() {
        notifyBody(notifyParams("99.99", "ch-1", "TRADE_SUCCESS"));

        RecordingObservability.AuditCall call = obs.audits().stream()
                .filter(a -> "payment.notify_rejected".equals(a.action()))
                .findFirst()
                .orElseThrow();
        assertThat(call.idempotencyKey()).isEqualTo(PAYMENT_NO);
        assertThat(call.entityId()).isEqualTo(PAYMENT_NO);
        assertThat(call.toStatus()).isEqualTo("REJECTED");
        // 币种取「被拒的那笔单的币种」：CNY 单 ⇒ CNY，与迁移前支付宝端点的记录逐字相同
        assertThat(call.currencyCode()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("拒绝响应不泄漏内部细节（不暴露期望金额/内部标识）[FR-245]")
    void rejectionBodyLeaksNoInternals() {
        String body = notifyBody(notifyParams("99.99", "ch-1", "TRADE_SUCCESS"));

        assertThat(body).doesNotContain("PM-VAL-1");
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("\tat ");
    }
}
