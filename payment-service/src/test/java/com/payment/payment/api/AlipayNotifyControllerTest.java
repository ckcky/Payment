package com.payment.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.AlipayGateway;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.config.AlipaySandboxProperties;
import com.payment.payment.support.PaymentTestStack;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 支付宝 notify 端点的 <b>HTTP 层</b>契约测试（spec 030 / T121，SC-A-14）。
 *
 * <p>与 {@link AlipayNotifyValidationTest} 的分工：那一类直接调 {@code controller.onNotify(params)}
 * 验<b>业务校验逻辑</b>；本类走 <b>MockMvc</b>，验的是<b>真实 HTTP 形状</b>——</p>
 * <ul>
 *   <li>{@code consumes = application/x-www-form-urlencoded} 的表单绑定是否真的生效
 *       （参数名/编码错一个，线上就是「验签永远失败」）；</li>
 *   <li>响应体在<b>经过消息转换器之后</b>是否<b>恰好</b>是纯文本 {@code success}
 *       ——直接调方法拿不到「转换器有没有加引号/加换行」这个信息（FR-206）；</li>
 *   <li>验签失败的状态码在 HTTP 层确实是 {@code 403}。</li>
 * </ul>
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 而非 {@code @SpringBootTest}：
 * 本类要验的是「这个 controller 的 HTTP 契约」，不需要拉起整个应用上下文
 * （那会牵入 Nacos / Feign / 数据源，把一个本该毫秒级的契约测试变成分钟级且易碎）。</p>
 */
class AlipayNotifyControllerTest {

    private static final String PAYMENT_NO = "PM-030-http-1";
    private static final String NOTIFY_PATH = "/internal/channels/alipay/notify";

    private InMemoryPaymentRepository payments;
    private InMemoryPaymentAttemptRepository attempts;
    private StubGateway gateway;
    private MockMvc mvc;

    /** 验签结果可控的最小网关桩。 */
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
        PaymentTestStack stack = new PaymentTestStack();
        payments = stack.payments;
        attempts = stack.attempts;

        payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1));
        attempts.save(PaymentAttempt.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX")));

        gateway = new StubGateway();
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId("sandbox-app-1");

        AlipayNotifyController controller = new AlipayNotifyController(gateway, properties,
                stack.callback, payments, attempts, new NoopBusinessMetrics(), new StructuredAuditLogger());

        // standalone：只装这一个 controller，走真实 MVC 参数绑定 + 消息转换
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    // ---- FR-206：响应体经过转换器后仍**恰好**是纯文本 success ----

    @Test
    @DisplayName("成功路径：HTTP 200 + 响应体**恰为**纯文本 success（无引号/无换行/无 JSON 包装）[FR-206]")
    void successBodyIsExactlyPlainTextSuccess() throws Exception {
        mvc.perform(post(NOTIFY_PATH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", PAYMENT_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("trade_no", "ch-txn-1")
                        .param("total_amount", "10.00")
                        .param("app_id", "sandbox-app-1")
                        .param("sign", "fake")
                        .param("sign_type", "RSA2"))
                .andExpect(status().isOk())
                // content().string(...) 是**精确**比较：多一个引号或换行都会失败
                .andExpect(content().string("success"));

        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- FR-202 / INV-10：验签失败 ⇒ 403 且状态零改动 ----

    @Test
    @DisplayName("验签失败：HTTP 403 + 状态零改动（不触达收敛）[FR-202][INV-10]")
    void signatureFailureIs403OverHttp() throws Exception {
        gateway.verifyResult = false;

        mvc.perform(post(NOTIFY_PATH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", PAYMENT_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("trade_no", "ch-txn-1")
                        .param("total_amount", "10.00")
                        .param("sign", "bad")
                        .param("sign_type", "RSA2"))
                .andExpect(status().isForbidden());

        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    // ---- FR-204：WAIT_BUYER_PAY 不推进到终态 ----

    @Test
    @DisplayName("WAIT_BUYER_PAY：200 success 但**不**推进终态（UNKNOWN 待收敛）[FR-204][CB-10]")
    void waitBuyerPayDoesNotAdvanceOverHttp() throws Exception {
        mvc.perform(post(NOTIFY_PATH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", PAYMENT_NO)
                        .param("trade_status", "WAIT_BUYER_PAY")
                        .param("trade_no", "ch-txn-1")
                        .param("total_amount", "10.00")
                        .param("app_id", "sandbox-app-1")
                        .param("sign", "fake")
                        .param("sign_type", "RSA2"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        PaymentStatus status = payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus();
        assertThat(status).isNotEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(status).isNotEqualTo(PaymentStatus.FAILED);
        assertThat(status).isEqualTo(PaymentStatus.UNKNOWN);
    }

    // ---- 媒体类型：只接受 form-urlencoded（FR-201） ----

    @Test
    @DisplayName("错误 Content-Type（application/json）⇒ 415（声明 consumes 生效）[FR-201]")
    void jsonContentTypeIsRejected() throws Exception {
        mvc.perform(post(NOTIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"out_trade_no\":\"" + PAYMENT_NO + "\"}"))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    // ---- FR-213：校验失败响应不含内部细节 ----

    @Test
    @DisplayName("金额不符：拒绝响应**不泄漏**内部期望值 / 堆栈（FR-245 边界）")
    void rejectionBodyLeaksNoInternals() throws Exception {
        String body = mvc.perform(post(NOTIFY_PATH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", PAYMENT_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("trade_no", "ch-txn-1")
                        .param("total_amount", "99.99")   // 应付款 10.00
                        .param("app_id", "sandbox-app-1")
                        .param("sign", "fake")
                        .param("sign_type", "RSA2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).startsWith("rejected");
        assertThat(body).doesNotContain("Exception").doesNotContain("at com.payment");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }
}
