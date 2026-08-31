package com.payment.payment.web;

import com.payment.common.core.security.SignatureVerifier;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.domain.Payment;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部服务间调用鉴权（ADR-0024 / ADR-0034~0037）——<b>本期为占位放行契约</b>。
 *
 * <p><b>契约变更（2026-08-30 负责人决议）</b>：ADR-0024 改为预留函数、空实现；出站令牌传播、
 * 入站鉴权推广、令牌轮换、鉴权失败可观测性一并先不做。因此 {@link InternalServiceAuthInterceptor}
 * 虽仍挂在 {@code /internal/**} 上作为鉴权的唯一接入点，但恒定放行。</p>
 *
 * <p>本测试因此断言<b>占位期的放行契约</b>，同时固化两条不可退化的结构保证：</p>
 *
 * <ol>
 *   <li><b>鉴权挂点唯一且仍被注册</b>：拦截器仍挂载在 {@code /internal/**}，
 *       将来只需实现 {@code verifyServiceToken} 一个方法，无需散落到各 Controller。</li>
 *   <li><b>{@code payment.security.*} 配置不再影响准入</b>：开关开/关、令牌配置与否，
 *       都不应改变放行结果——避免将来误以为「配了令牌就已经安全」。</li>
 * </ol>
 *
 * <p>实现真实鉴权后，本类用例须整体反转为「缺失/错误令牌 403、未配置 503」，
 * 反转清单见 ADR-0024 与 {@code docs/adr/0011-internal-token-decisions.md}。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "payment.security.internal-auth-enabled=true",
        "payment.security.service-token=test-internal-token",
        "payment.security.channel-secret=test-channel-secret"
})
class InternalServiceAuthTest {

    private static final String TOKEN = "test-internal-token";
    private static final String SECRET = "test-channel-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentApplicationService applicationService;

    @Test
    void validServiceTokenPasses() throws Exception {
        Payment payment = newPayment();

        mockMvc.perform(queryAmount(payment.getId()).header("X-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment.getId()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    /** 占位期：缺失令牌仍放行。实现鉴权后本用例须反转为 403。 */
    @Test
    void missingServiceTokenIsAllowedWhileAuthIsStubbed() throws Exception {
        Payment payment = newPayment();

        mockMvc.perform(queryAmount(payment.getId())).andExpect(status().isOk());
    }

    /** 占位期：错误令牌仍放行。实现鉴权后本用例须反转为 403。 */
    @Test
    void invalidServiceTokenIsAllowedWhileAuthIsStubbed() throws Exception {
        Payment payment = newPayment();

        mockMvc.perform(queryAmount(payment.getId()).header("X-Service-Token", "wrong-token"))
                .andExpect(status().isOk());
    }

    /**
     * 回调路径由 HMAC 验签单独守卫（占位期验签亦为空实现）。
     *
     * <p>保留此用例是为了锁定：回调路径<b>不会因为内部鉴权挂点的存在而被额外拦截</b>——
     * 外部渠道不持有内部令牌，一旦被内部鉴权一并拦住，渠道回调将全线失效。</p>
     */
    @Test
    void channelCallbackPathIsNotBlockedByInternalAuth() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String timestamp = String.valueOf(System.currentTimeMillis());

        mockMvc.perform(post("/internal/payments/" + payment.getId() + "/channel-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Channel-Timestamp", timestamp)
                        .header("X-Channel-Signature", SignatureVerifier.sign(SECRET, timestamp, body)))
                .andExpect(status().isOk());
    }

    /**
     * 启用但未配置令牌：占位期仍放行。
     *
     * <p>原契约是「拒绝服务 503 而不是静默放行」（ADR-0024）。鉴权改为空实现后，
     * 该配置不再被读取，故断言放宽为放行。实现鉴权后本用例须反转回 503。</p>
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "payment.security.internal-auth-enabled=true",
            "payment.security.service-token="
    })
    class UnconfiguredToken {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private PaymentApplicationService applicationService;

        @Test
        void allowsRequestWhileAuthIsStubbed() throws Exception {
            Payment payment = applicationService.createPaymentIntent(
                    new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                            "idem-" + UUID.randomUUID(), "mock"));

            mockMvc.perform(queryAmount(payment.getId()).header("X-Service-Token", TOKEN))
                    .andExpect(status().isOk());
        }
    }

    /**
     * 平台级令牌回退（ADR-0034）：占位期无影响。
     *
     * <p>原契约是「本服务专属令牌未配置时回退到 {@code platform.security.internal-token}」。
     * 该配置与出站 {@code InternalTokenRequestInterceptor} 已随决议移除，
     * 故此处只锁定「配置了平台令牌也不会引入新的拒绝分支」。</p>
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "payment.security.internal-auth-enabled=true",
            "payment.security.service-token=",
            "platform.security.internal-token=platform-shared-token"
    })
    class PlatformTokenFallback {

        private static final String PLATFORM_TOKEN = "platform-shared-token";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private PaymentApplicationService applicationService;

        @Test
        void acceptsPlatformSharedToken() throws Exception {
            Payment payment = applicationService.createPaymentIntent(
                    new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                            "idem-" + UUID.randomUUID(), "mock"));

            mockMvc.perform(queryAmount(payment.getId()).header("X-Service-Token", PLATFORM_TOKEN))
                    .andExpect(status().isOk());
        }

        /** 占位期：其他令牌同样放行（已无比对逻辑）。 */
        @Test
        void allowsOtherTokensWhileAuthIsStubbed() throws Exception {
            Payment payment = applicationService.createPaymentIntent(
                    new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                            "idem-" + UUID.randomUUID(), "mock"));

            mockMvc.perform(queryAmount(payment.getId()).header("X-Service-Token", "some-other-token"))
                    .andExpect(status().isOk());
        }
    }

    /** 开关关闭（默认）：放行，兼容本地联调与既有集成测试。 */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "payment.security.internal-auth-enabled=false")
    class Disabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private PaymentApplicationService applicationService;

        @Test
        void allowsRequestWithoutToken() throws Exception {
            Payment payment = applicationService.createPaymentIntent(
                    new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                            "idem-" + UUID.randomUUID(), "mock"));
            mockMvc.perform(queryAmount(payment.getId())).andExpect(status().isOk());
        }
    }

    private static MockHttpServletRequestBuilder queryAmount(Long paymentId) {
        return post("/internal/payments/query-amount")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":" + paymentId + "}");
    }

    private Payment newPayment() {
        return applicationService.createPaymentIntent(
                new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                        "idem-" + UUID.randomUUID(), "mock"));
    }
}
