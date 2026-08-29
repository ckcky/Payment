package com.payment.payment.web;

import com.payment.common.core.security.SignatureVerifier;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentCallbackService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渠道回调验签（ADR-0025 / FR-001~FR-002 / SC-001）。
 *
 * <p>核心断言：签名校验发生在<b>业务处理之前</b>——任何未通过校验的回调都不会触达
 * {@link PaymentCallbackService}，因此不可能翻转支付状态、触发履约或记账。
 * 这里用 {@code @MockBean} 直接观测「业务处理是否被调用」，避免依赖支付状态的间接推断。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "payment.security.channel-secret=test-channel-secret",
        "payment.security.signature-replay-window-ms=300000"
})
class ChannelCallbackSecurityTest {

    private static final String SECRET = "test-channel-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentApplicationService applicationService;

    @MockBean
    private PaymentCallbackService callbackService;

    @Test
    void validSignatureIsDelegatedToCallbackService() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\",\"amountMinor\":100}";

        mockMvc.perform(signed(payment.getId(), body)).andExpect(status().isOk());

        ArgumentCaptor<ChannelResult> captor = ArgumentCaptor.forClass(ChannelResult.class);
        verify(callbackService, times(1)).handleCallback(eq(payment.getId()), captor.capture());
        // 原始 body 必须被完整读取并正确反序列化（CachedBodyHttpServletRequest 生效）
        assertThat(captor.getValue().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(captor.getValue().channelReference()).isEqualTo("ch-ref-1");
    }

    @Test
    void invalidSignatureIsRejectedAndNeverDelegated() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String timestamp = now();

        mockMvc.perform(callback(payment.getId(), body, timestamp, "deadbeef"))
                .andExpect(status().isForbidden());

        verify(callbackService, never()).handleCallback(any(), any());
    }

    @Test
    void missingSignatureHeadersIsRejected() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";

        mockMvc.perform(post(url(payment.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        verify(callbackService, never()).handleCallback(any(), any());
    }

    @Test
    void timestampOutsideReplayWindowIsRejected() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String staleTimestamp = String.valueOf(System.currentTimeMillis() - 600_000L);

        // 签名本身正确，但时间戳超出 5min 防重放窗口 → 拒绝（防重放）
        mockMvc.perform(callback(payment.getId(), body, staleTimestamp,
                        SignatureVerifier.sign(SECRET, staleTimestamp, body)))
                .andExpect(status().isForbidden());

        verify(callbackService, never()).handleCallback(any(), any());
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        Payment payment = newPayment();
        String signedBody = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String tamperedBody = "{\"status\":\"FAILURE\",\"channelReference\":\"ch-ref-1\"}";
        String timestamp = now();

        mockMvc.perform(callback(payment.getId(), tamperedBody, timestamp,
                        SignatureVerifier.sign(SECRET, timestamp, signedBody)))
                .andExpect(status().isForbidden());

        verify(callbackService, never()).handleCallback(any(), any());
    }

    /** 未配置验签密钥：验签不可关闭，配置缺失只能拒绝服务（ADR-0026），不能静默放行。 */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "payment.security.channel-secret=")
    class UnconfiguredSecret {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void returnsServiceUnavailableInsteadOfAllowing() throws Exception {
            String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
            String timestamp = now();

            mockMvc.perform(callback(1L, body, timestamp, SignatureVerifier.sign(SECRET, timestamp, body)))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    private Payment newPayment() {
        return applicationService.createPaymentIntent(
                new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                        "idem-" + UUID.randomUUID(), "mock"));
    }

    private static String url(Long paymentId) {
        return "/internal/payments/" + paymentId + "/channel-callback";
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static MockHttpServletRequestBuilder signed(Long paymentId, String body) {
        String timestamp = now();
        return callback(paymentId, body, timestamp, SignatureVerifier.sign(SECRET, timestamp, body));
    }

    private static MockHttpServletRequestBuilder callback(Long paymentId, String body,
                                                          String timestamp, String signature) {
        return post(url(paymentId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Channel-Timestamp", timestamp)
                .header("X-Channel-Signature", signature);
    }
}
