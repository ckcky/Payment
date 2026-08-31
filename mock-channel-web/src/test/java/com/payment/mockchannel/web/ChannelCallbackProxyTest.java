package com.payment.mockchannel.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.security.SignatureVerifier;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChannelCallbackProxy} 签名头生成逻辑单测（ADR-0048 修订版 + ADR-0052）。
 *
 * <p>核心契约：VALID 模式的签名头与 payment-service 的验签实现互验通过；
 * FORGED 模式用错误密钥签名、验签必然失败（演示 403 fail-closed）；NONE 模式不带签名头。</p>
 */
class ChannelCallbackProxyTest {

    private static final String SECRET = "demo-channel-secret";
    private static final String BODY = "{\"status\":\"SUCCESS\",\"channelReference\":\"demo-ref-1\","
            + "\"reason\":null,\"amountMinor\":9900}";

    @Test
    @DisplayName("VALID：签名头与 payment 侧 SignatureVerifier 互验通过")
    void validSignatureVerifies() {
        Map<String, String> headers = ChannelCallbackProxy.signHeaders(SECRET, BODY, "VALID");

        assertThat(headers).containsKeys("X-Channel-Timestamp", "X-Channel-Signature");
        boolean ok = SignatureVerifier.verify(SECRET, headers.get("X-Channel-Timestamp"), BODY,
                headers.get("X-Channel-Signature"), System.currentTimeMillis(), 300_000L);
        assertThat(ok).as("mock-channel-web 生成的签名必须能被 payment-service 验签通过").isTrue();
    }

    @Test
    @DisplayName("FORGED：错误密钥签名，payment 侧验签必然失败")
    void forgedSignatureFailsVerification() {
        Map<String, String> headers = ChannelCallbackProxy.signHeaders(SECRET, BODY, "FORGED");

        boolean ok = SignatureVerifier.verify(SECRET, headers.get("X-Channel-Timestamp"), BODY,
                headers.get("X-Channel-Signature"), System.currentTimeMillis(), 300_000L);
        assertThat(ok).as("伪造签名必须被 payment-service 验签拒绝（403 fail-closed 演示）").isFalse();
    }

    @Test
    @DisplayName("NONE：不带任何签名头（演示缺头被拒）")
    void noneModeHasNoHeaders() {
        Map<String, String> headers = ChannelCallbackProxy.signHeaders(SECRET, BODY, "NONE");

        assertThat(headers).isEmpty();
    }

    @Test
    @DisplayName("未知 signMode 返回 null（代理层转 400）")
    void unknownModeReturnsNull() {
        assertThat(ChannelCallbackProxy.signHeaders(SECRET, BODY, "BOGUS")).isNull();
    }

    @Test
    @DisplayName("SimpleJson：null → null、number 裸值、string 加引号转义")
    void simpleJsonSerialization() {
        String json = ChannelCallbackProxy.SimpleJson.serialize(new java.util.LinkedHashMap<>(Map.of(
                "status", "SUCCESS",
                "amountMinor", 9900,
                "reason", "a\"b\\c")));

        assertThat(json).contains("\"status\":\"SUCCESS\"");
        assertThat(json).contains("\"amountMinor\":9900");
        assertThat(json).contains("\"reason\":\"a\\\"b\\\\c\"");
        assertThat(json).startsWith("{").endsWith("}");
    }
}
