package com.payment.payment.api;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.channel.AlipayGateway;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.config.AlipaySandboxProperties;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 030 / Phase 8：支付宝 notify 三段式校验（FR-202 / FR-203 / FR-210~FR-212）。
 *
 * <p>锁死的核心行为：
 * <ol>
 *   <li><b>验签失败 ⇒ 403 且不触达收敛</b>（FR-202 / INV-10）——状态一个字节都不许动；</li>
 *   <li><b>金额 / 币种不符 ⇒ 拒绝推进</b>（FR-210 / FR-211）且留下三件套痕迹（FR-213）；</li>
 *   <li><b>响应体恰好 {@code success}</b>（FR-206）且校验失败时不返回它；</li>
 *   <li><b>正常路径收敛成功</b>且复用既有链路（终态吸收 / 幂等）。</li>
 * </ol>
 */
class AlipayNotifyValidationTest {

    private static final String PAYMENT_NO = "PM-030-1";

    private InMemoryPaymentRepository payments;
    private InMemoryPaymentAttemptRepository attempts;
    private PaymentTestStack stack;
    private StubGateway gateway;
    private AlipaySandboxProperties properties;
    private AlipayNotifyController controller;

    /** 网关桩：验签结果可控；pagePay/query/refund 不用。 */
    private static final class StubGateway implements AlipayGateway {
        boolean verifyResult = true;

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
            return verifyResult;
        }
    }

    @BeforeEach
    void setUp() {
        stack = new PaymentTestStack();
        payments = stack.payments;
        attempts = stack.attempts;

        // 一张 PROCESSING 支付单 + 对应 attempt（已受理、渠道引用为 null——沙箱下单时的真实形态）
        Payment payment = Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001");
        payments.save(payment);
        attempts.save(PaymentAttempt.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX")));

        gateway = new StubGateway();
        properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId("sandbox-app-1");

        controller = new AlipayNotifyController(gateway, properties, stack.callback,
                payments, attempts, new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    private static Map<String, String> notify(String totalAmount, String tradeNo, String status) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", status);
        params.put("trade_no", tradeNo);
        params.put("total_amount", totalAmount);
        params.put("app_id", "sandbox-app-1");
        params.put("sign", "fake");
        params.put("sign_type", "RSA2");
        return params;
    }

    // ---- ① Signature Validation（FR-202） ----

    @Test
    @DisplayName("验签失败 ⇒ 403 且**不触达**收敛（状态零改动）[FR-202][INV-10]")
    void signatureFailureBlocksEverything() {
        gateway.verifyResult = false;

        ResponseEntity<String> response = controller.onNotify(
                notify("10.00", "ch-txn-1", "TRADE_SUCCESS"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // 关键断言：支付单状态一个字节都没动
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getChannelReference()).isNull();
    }

    // ---- ③ Amount / Currency Validation（FR-210 / FR-211） ----

    @Test
    @DisplayName("金额不符 ⇒ 拒绝推进，状态保持 PROCESSING [FR-210][CB-5]")
    void amountMismatchRejects() {
        ResponseEntity<String> response = controller.onNotify(
                notify("99.99", "ch-txn-1", "TRADE_SUCCESS")); // 应付款 10.00

        assertThat(response.getBody()).contains("rejected");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    @DisplayName("金额一致 ⇒ 正常收敛为 SUCCEEDED 且响应体恰好 success [FR-206]")
    void amountMatchConverges() {
        ResponseEntity<String> response = controller.onNotify(
                notify("10.00", "ch-txn-1", "TRADE_SUCCESS"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("success"); // 恰好，无引号无换行
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("金额缺失 ⇒ 不校验（向后兼容既有 mock 回调形态）[FR-210 尾注]")
    void missingAmountSkipsValidation() {
        ResponseEntity<String> response = controller.onNotify(
                notify(null, "ch-txn-1", "TRADE_SUCCESS"));

        assertThat(response.getBody()).isEqualTo("success");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("app_id 不符 ⇒ 拒绝推进 [FR-203]")
    void appIdMismatchRejects() {
        Map<String, String> params = notify("10.00", "ch-txn-1", "TRADE_SUCCESS");
        params.put("app_id", "someone-elses-app");

        ResponseEntity<String> response = controller.onNotify(params);

        assertThat(response.getBody()).contains("rejected");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    // ---- ② Channel Reference Validation（FR-212） ----

    @Test
    @DisplayName("本单已记录引用且不一致 ⇒ 拒绝（防串号）[FR-212][CB-7]")
    void channelReferenceMismatchRejects() {
        // 先给 attempt 落一个引用（模拟受理后已回填）
        PaymentAttempt attempt = attempts.findByPaymentNo(PAYMENT_NO).get(0);
        attempt.backfillChannelReference("ch-txn-original");
        attempts.save(attempt);

        // 通知却带了另一个交易号
        ResponseEntity<String> response = controller.onNotify(
                notify("10.00", "ch-txn-DIFFERENT", "TRADE_SUCCESS"));

        assertThat(response.getBody()).contains("rejected");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    // ---- 状态映射（FR-204 / CB-10） ----

    @Test
    @DisplayName("WAIT_BUYER_PAY ⇒ 收敛为 UNKNOWN 待收敛态，**绝不臆断成功或失败** [FR-204]")
    void waitBuyerPayDoesNotAdvanceToTerminal() {
        ResponseEntity<String> response = controller.onNotify(
                notify("10.00", "ch-txn-1", "WAIT_BUYER_PAY"));

        assertThat(response.getBody()).isEqualTo("success"); // 已收到，渠道无需重推
        // 关键：**没有**落 SUCCEEDED（钱没到）也**没有**落 FAILED（买家可能马上付）。
        // 从 PROCESSING 迁到 UNKNOWN 是「我不确定，交给主动查询/后续回调收敛」，
        // 这正是「不臆断」在状态机上的体现。终态只有权威结果才能落。
        PaymentStatus status = payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus();
        assertThat(status).isNotEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(status).isNotEqualTo(PaymentStatus.FAILED);
        assertThat(status).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("TRADE_CLOSED ⇒ 收敛为 FAILED [FR-204]")
    void tradeClosedFails() {
        controller.onNotify(notify("10.00", "ch-txn-1", "TRADE_CLOSED"));

        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    // ---- 幂等（SC-B2-05 / T103） ----

    @Test
    @DisplayName("重复回调被幂等吸收，**不**误判为金额不一致而反复拒绝 [SC-B2-05]")
    void duplicateCallbackIsAbsorbed() {
        ResponseEntity<String> first = controller.onNotify(notify("10.00", "ch-txn-1", "TRADE_SUCCESS"));
        ResponseEntity<String> second = controller.onNotify(notify("10.00", "ch-txn-1", "TRADE_SUCCESS"));

        assertThat(first.getBody()).isEqualTo("success");
        // 第二次同样返回 success：重复回调是正常现象，不该被判为校验失败
        assertThat(second.getBody()).isEqualTo("success");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- 审计痕迹（FR-213 / FR-293） ----

    @Test
    @DisplayName("校验失败不产生状态迁移（§9.4）：拒绝即拒绝，不留半个写入 [FR-213]")
    void rejectionLeavesNoPartialWrite() {
        Payment before = payments.findByPaymentNo(PAYMENT_NO).orElseThrow();
        String statusBefore = before.getStatus().name();
        Integer versionBefore = before.getVersion();

        controller.onNotify(notify("99.99", "ch-txn-1", "TRADE_SUCCESS"));

        Payment after = payments.findByPaymentNo(PAYMENT_NO).orElseThrow();
        assertThat(after.getStatus().name()).isEqualTo(statusBefore);
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        // attempt 也不该被推进
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.ACCEPTED);
    }
}
