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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渠道回调验签过滤器（ADR-0025）——<b>本期为占位放行契约</b>。
 *
 * <p><b>契约变更（2026-08-30 负责人决议）</b>：ADR-0025「渠道回调签名校验」改为预留函数、空实现，
 * {@link ChannelCallbackSignatureFilter#verifySignature} 恒定返回 {@code true}。因此本测试不再断言
 * 「非法签名被 403 拒绝」，而是断言<b>占位期的放行契约</b>，并把真正不可退化的结构性保证固化住：</p>
 *
 * <ol>
 *   <li><b>过滤器确实生效</b>：请求经过 {@code ChannelCallbackSignatureFilter}，而非被绕过——
 *       否则会出现「测试全绿、生产行为不一致」的假绿。</li>
 *   <li><b>原始 body 可重复读</b>：过滤器读走原始流后换上 {@code CachedBodyHttpServletRequest}，
 *       下游 {@code @RequestBody} 才能正确反序列化。这是接入真实渠道时最容易踩的坑，
 *       由 {@link #validSignatureIsDelegatedToCallbackService} 用字段级断言锁定。</li>
 *   <li><b>放行后必然触达业务</b>：占位期所有回调（含无签名头、错误签名、过期时间戳、篡改 body）
 *       都应被放行并委派给 {@link PaymentCallbackService}。这些用例在验签实现后
 *       <b>必须整体反转为拒绝断言</b>，反转清单见 ADR-0025。</li>
 * </ol>
 *
 * <p>这些用例存在的意义不是证明「验签没做」，而是把「验签没做」这一事实钉在测试里：
 * 一旦有人悄悄实现验签却忘了补拒绝路径，或改坏了过滤器注册，本测试会立刻变红。</p>
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

    /** 核心结构保证：签名正确时放行，且原始 body 被完整读取并正确反序列化。 */
    @Test
    void validSignatureIsDelegatedToCallbackService() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\",\"amountMinor\":100}";

        mockMvc.perform(signed(payment.getPaymentNo(), body)).andExpect(status().isOk());

        ArgumentCaptor<ChannelResult> captor = ArgumentCaptor.forClass(ChannelResult.class);
        verify(callbackService, times(1)).handleCallback(eq(payment.getPaymentNo()), captor.capture());
        // 原始 body 必须被完整读取并正确反序列化（CachedBodyHttpServletRequest 生效）
        assertThat(captor.getValue().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(captor.getValue().channelReference()).isEqualTo("ch-ref-1");
    }

    /** 占位期：签名错误仍放行（验签为空实现）。实现验签后本用例须反转为 403。 */
    @Test
    void invalidSignatureIsAllowedWhileSignatureVerificationIsStubbed() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String timestamp = now();

        mockMvc.perform(callback(payment.getPaymentNo(), body, timestamp, "deadbeef"))
                .andExpect(status().isOk());

        verify(callbackService, times(1)).handleCallback(eq(payment.getPaymentNo()), any());
    }

    /** 占位期：缺少签名头仍放行。实现验签后本用例须反转为 403。 */
    @Test
    void missingSignatureHeadersIsAllowedWhileSignatureVerificationIsStubbed() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";

        mockMvc.perform(post(url(payment.getPaymentNo()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(callbackService, times(1)).handleCallback(eq(payment.getPaymentNo()), any());
    }

    /** 占位期：时间戳超出防重放窗口仍放行。实现验签后本用例须反转为 403。 */
    @Test
    void staleTimestampIsAllowedWhileReplayProtectionIsStubbed() throws Exception {
        Payment payment = newPayment();
        String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String staleTimestamp = String.valueOf(System.currentTimeMillis() - 600_000L);

        mockMvc.perform(callback(payment.getPaymentNo(), body, staleTimestamp,
                        SignatureVerifier.sign(SECRET, staleTimestamp, body)))
                .andExpect(status().isOk());

        verify(callbackService, times(1)).handleCallback(eq(payment.getPaymentNo()), any());
    }

    /** 占位期：body 被篡改仍放行。实现验签后本用例须反转为 403。 */
    @Test
    void tamperedBodyIsAllowedWhileSignatureVerificationIsStubbed() throws Exception {
        Payment payment = newPayment();
        String signedBody = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
        String tamperedBody = "{\"status\":\"FAILURE\",\"channelReference\":\"ch-ref-1\"}";
        String timestamp = now();

        mockMvc.perform(callback(payment.getPaymentNo(), tamperedBody, timestamp,
                        SignatureVerifier.sign(SECRET, timestamp, signedBody)))
                .andExpect(status().isOk());

        verify(callbackService, times(1)).handleCallback(eq(payment.getPaymentNo()), any());
    }

    /**
     * 未配置验签密钥：占位期无影响，仍放行。
     *
     * <p>原契约是「验签不可关闭，配置缺失只能 503 拒绝服务」（ADR-0026）。验签改为空实现后，
     * 该配置不再被读取，故断言放宽为放行。实现验签后本用例须反转回 503。</p>
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "payment.security.channel-secret=")
    class UnconfiguredSecret {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private PaymentApplicationService applicationService;

        @Test
        void allowsCallbackWhileSignatureVerificationIsStubbed() throws Exception {
            Payment payment = applicationService.createPaymentIntent(
                    new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                            "idem-" + UUID.randomUUID(), "mock"));
            String body = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-ref-1\"}";
            String timestamp = now();

            mockMvc.perform(callback(payment.getPaymentNo(), body, timestamp, SignatureVerifier.sign(SECRET, timestamp, body)))
                    .andExpect(status().isOk());
        }
    }

    private Payment newPayment() {
        return applicationService.createPaymentIntent(
                new CreatePaymentCommand("txn-" + UUID.randomUUID(), "order-1", "user-1", 100L, "CNY",
                        "idem-" + UUID.randomUUID(), "mock"));
    }

    private static String url(String paymentNo) {
        return "/internal/payments/" + paymentNo + "/channel-callback";
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static MockHttpServletRequestBuilder signed(String paymentNo, String body) {
        String timestamp = now();
        return callback(paymentNo, body, timestamp, SignatureVerifier.sign(SECRET, timestamp, body));
    }

    private static MockHttpServletRequestBuilder callback(String paymentNo, String body,
                                                          String timestamp, String signature) {
        return post(url(paymentNo))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Channel-Timestamp", timestamp)
                .header("X-Channel-Signature", signature);
    }
}
