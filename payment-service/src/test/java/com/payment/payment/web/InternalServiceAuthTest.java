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
 * 内部服务间调用鉴权（ADR-0024 / FR-003 / SC-002）。
 *
 * <p>{@code /internal/**} 端点（退款金额查询、退款尝试、对账事实）一旦裸露即可被越权调用，
 * MVP 以共享令牌 {@code X-Service-Token} 守卫；渠道回调路径除外——外部渠道不持有内部令牌，
 * 其安全性由 HMAC 验签独立保证。</p>
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

    @Test
    void missingServiceTokenIsRejected() throws Exception {
        mockMvc.perform(queryAmount(1L)).andExpect(status().isForbidden());
    }

    @Test
    void invalidServiceTokenIsRejected() throws Exception {
        mockMvc.perform(queryAmount(1L).header("X-Service-Token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    /** 回调路径由 HMAC 验签单独守卫，不要求内部服务令牌（外部渠道不持有该令牌）。 */
    @Test
    void channelCallbackPathIsExemptFromServiceToken() throws Exception {
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

    /** 启用但未配置令牌：拒绝服务（503）而不是静默放行（ADR-0024）。 */
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

        @Test
        void returnsServiceUnavailable() throws Exception {
            mockMvc.perform(queryAmount(1L).header("X-Service-Token", TOKEN))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    /**
     * 入站令牌回退到平台级配置（ADR-0034）。
     *
     * <p>本服务专属令牌未配置时，回退到 {@code platform.security.internal-token}——正是出站
     * {@code InternalTokenRequestInterceptor} 携带的那一把。两端同源，
     * {@code internal-auth-enabled=true} 才可能安全开启（否则调用方全线 403）。</p>
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

        @Test
        void stillRejectsOtherTokens() throws Exception {
            mockMvc.perform(queryAmount(1L).header("X-Service-Token", "some-other-token"))
                    .andExpect(status().isForbidden());
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
