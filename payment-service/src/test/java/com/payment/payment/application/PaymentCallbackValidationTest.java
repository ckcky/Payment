package com.payment.payment.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.payment.api.AlipayNotifyController;
import com.payment.payment.infra.channel.alipay.AlipayGateway;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.config.AlipaySandboxProperties;
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
 * <p>{@code AlipayNotifyValidationTest} 覆盖了「金额/引用不符 ⇒ 拒绝 + 状态不动」；
 * 本类把断言补到<b>完整三件套</b>——<b>不推进 + 计指标 + 写审计</b>，逐类验证，
 * 并额外锁死「诚实拒绝」的反面：校验<b>通过</b>时绝不能留下拒绝痕迹（否则指标
 * 会永久高位而让人以为系统一直在拒单）。</p>
 *
 * <p>为什么三件套要一起断言：静默拒绝是最危险的形态——状态没动看起来「一切正常」，
 * 差异被吞进黑洞，直到对账日才暴露。指标负责「有异常」，审计负责「是哪一笔」，
 * 缺任何一个都无法在事故中定位。</p>
 */
class PaymentCallbackValidationTest {

    private static final String PAYMENT_NO = "PM-VAL-1";

    private PaymentTestStack stack;
    private InMemoryPaymentRepository payments;
    private InMemoryPaymentAttemptRepository attempts;
    private RecordingObservability obs;
    private StubGateway gateway;
    private AlipaySandboxProperties properties;
    private AlipayNotifyController controller;

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
        attempts.save(PaymentAttempt.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX")));

        gateway = new StubGateway();
        properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId("sandbox-app-1");

        // 用记录式观测装配真实收敛服务：这样指标/审计都进内存，可断言
        PaymentResultProcessor processor = new PaymentResultProcessor(payments, attempts, stack.order);
        PaymentCallbackService callback = new PaymentCallbackService(processor, payments, obs.metrics, obs.audit);
        controller = new AlipayNotifyController(gateway, properties, callback,
                payments, attempts, obs.metrics, obs.audit);
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
        params.put("app_id", "sandbox-app-1");
        params.put("sign", "fake");
        params.put("sign_type", "RSA2");
        return params;
    }

    /** 三件套的统一断言：状态不动 + 该 reason 的指标 + 审计留痕。 */
    private void assertRejectedTriple(String expectedReasonTag) {
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("① 不推进：状态与 version 都不得变")
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                .as("① 不推进：attempt 也不得被推进")
                .isEqualTo(PaymentAttemptStatus.ACCEPTED);

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
            assertThat(controller.onNotify(notifyParams("9.99", "ch-1", "TRADE_SUCCESS")).getBody())
                    .contains("rejected");
            assertRejectedTriple("amount");
        }

        @Test
        @DisplayName("金额多付 ⇒ 同样拒绝（多付也是分歧，不能静默接受）[SC-B2-01]")
        void overpaidRejects() {
            assertThat(controller.onNotify(notifyParams("10.01", "ch-1", "TRADE_SUCCESS")).getBody())
                    .contains("rejected");
            assertRejectedTriple("amount");
        }

        @Test
        @DisplayName("金额恰好一致 ⇒ 收敛成功且**无**拒绝痕迹（诚实拒绝的反面）")
        void exactAmountLeavesNoRejectionTrace() {
            assertThat(controller.onNotify(notifyParams("10.00", "ch-1", "TRADE_SUCCESS")).getBody())
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
            assertThat(controller.onNotify(notifyParams(null, "ch-1", "TRADE_SUCCESS")).getBody())
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

            assertThat(controller.onNotify(notifyParams("10.00", "ch-1", "TRADE_SUCCESS")).getBody())
                    .contains("rejected");
            assertRejectedTriple("currency");
        }

        @Test
        @DisplayName("币种校验失败**优先于**收敛：绝不因币种问题仍落成功 [SC-B2-02]")
        void currencyRejectPreventsConvergence() {
            payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                    10_00L, "USD", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));

            controller.onNotify(notifyParams("10.00", "ch-1", "TRADE_SUCCESS"));

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
            PaymentAttempt attempt = attempts.findByPaymentNo(PAYMENT_NO).get(0);
            attempt.backfillChannelReference("ch-original");
            attempts.save(attempt);

            assertThat(controller.onNotify(notifyParams("10.00", "ch-DIFFERENT", "TRADE_SUCCESS")).getBody())
                    .contains("rejected");
            assertRejectedTriple("channel_reference");
        }

        @Test
        @DisplayName("引用一致 ⇒ 正常收敛，不留拒绝痕迹 [SC-B2-04]")
        void matchingReferenceConverges() {
            PaymentAttempt attempt = attempts.findByPaymentNo(PAYMENT_NO).get(0);
            attempt.backfillChannelReference("ch-1");
            attempts.save(attempt);

            assertThat(controller.onNotify(notifyParams("10.00", "ch-1", "TRADE_SUCCESS")).getBody())
                    .isEqualTo("success");
            assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(obs.countOf("payment.notify_rejected")).isZero();
        }

        @Test
        @DisplayName("受理时引用为空 ⇒ 首次通知正是回填时机，不算不一致（不误拒）")
        void nullRecordedReferenceIsBackfillOpportunity() {
            // setUp 里 attempt 的 channelReference 本就是 null
            assertThat(controller.onNotify(notifyParams("10.00", "ch-first", "TRADE_SUCCESS")).getBody())
                    .isEqualTo("success");
            assertThat(obs.countOf("payment.notify_rejected")).isZero();
        }

        @Test
        @DisplayName("app_id 不符 ⇒ 拒绝并计入 app_id 维度 [FR-203]")
        void appIdMismatchRejects() {
            Map<String, String> params = notifyParams("10.00", "ch-1", "TRADE_SUCCESS");
            params.put("app_id", "someone-elses");

            assertThat(controller.onNotify(params).getBody()).contains("rejected");
            assertRejectedTriple("app_id");
        }
    }

    // ---- ④ 审计字段正确性（FR-293） ----

    @Test
    @DisplayName("拒绝审计携带 paymentNo 与 REJECTED 终态（可反查到具体哪一笔）[FR-293]")
    void rejectionAuditCarriesPaymentIdentity() {
        controller.onNotify(notifyParams("99.99", "ch-1", "TRADE_SUCCESS"));

        RecordingObservability.AuditCall call = obs.audits().stream()
                .filter(a -> "payment.notify_rejected".equals(a.action()))
                .findFirst()
                .orElseThrow();
        assertThat(call.idempotencyKey()).isEqualTo(PAYMENT_NO);
        assertThat(call.entityId()).isEqualTo(PAYMENT_NO);
        assertThat(call.toStatus()).isEqualTo("REJECTED");
        assertThat(call.currencyCode()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("拒绝响应不泄漏内部细节（不暴露期望金额/内部标识）[FR-245]")
    void rejectionBodyLeaksNoInternals() {
        String body = controller.onNotify(notifyParams("99.99", "ch-1", "TRADE_SUCCESS")).getBody();

        assertThat(body).doesNotContain("PM-VAL-1");
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("\tat ");
    }
}
