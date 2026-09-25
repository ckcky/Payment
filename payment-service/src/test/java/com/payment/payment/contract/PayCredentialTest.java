package com.payment.payment.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.dto.channel.PayCredential;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PayCredential} 语义测试（spec 030 / T118 / FR-111，SC-A-02）。
 *
 * <p>核心是 {@code isRedirectFamily()}：消费端据此决定能不能直接
 * {@code window.open(payload)}。把二维码 / JSAPI / client_secret 误判为跳转家族会让
 * 前端把一坨参数当 URL 打开——所以六种 {@code Kind} 必须逐个核对。</p>
 */
class PayCredentialTest {

    @Test
    @DisplayName("REDIRECT_URL 属跳转家族（支付宝 pagePay 场景）")
    void redirectUrlIsRedirectFamily() {
        PayCredential c = PayCredential.redirectUrl("https://openapi.alipay.com/gateway.do?...", null);

        assertThat(c.kind()).isEqualTo(PayCredential.Kind.REDIRECT_URL);
        assertThat(c.isRedirectFamily()).isTrue();
    }

    @Test
    @DisplayName("H5_URL 属跳转家族（需在 WebView 打开，仍可直接打开）")
    void h5UrlIsRedirectFamily() {
        PayCredential c = PayCredential.h5Url("https://h5.example/pay", null);

        assertThat(c.kind()).isEqualTo(PayCredential.Kind.H5_URL);
        assertThat(c.isRedirectFamily()).isTrue();
    }

    @Test
    @DisplayName("二维码非跳转家族：内容串需消费端自行渲染成图，直接 open 会出错")
    void qrCodeIsNotRedirectFamily() {
        PayCredential c = PayCredential.qrCode("weixin://wxpay/bizpayurl?pr=abc", null);

        assertThat(c.kind()).isEqualTo(PayCredential.Kind.QR_CODE);
        assertThat(c.isRedirectFamily()).isFalse();
    }

    @Test
    @DisplayName("FORM_HTML / JSAPI_PARAMS / CLIENT_SECRET 均非跳转家族")
    void otherKindsAreNotRedirectFamily() {
        assertThat(new PayCredential(PayCredential.Kind.FORM_HTML, "<form>...</form>", null)
                .isRedirectFamily()).isFalse();
        assertThat(new PayCredential(PayCredential.Kind.JSAPI_PARAMS, "{\"appId\":\"...\"}", null)
                .isRedirectFamily()).isFalse();
        assertThat(new PayCredential(PayCredential.Kind.CLIENT_SECRET, "pi_1_secret_abc", null)
                .isRedirectFamily()).isFalse();
    }

    @Test
    @DisplayName("六种 Kind 全部可构造且往返保真（枚举完整性）")
    void allSixKindsRoundTrip() {
        for (PayCredential.Kind kind : PayCredential.Kind.values()) {
            PayCredential c = new PayCredential(kind, "payload-" + kind, null);
            assertThat(c.kind()).isEqualTo(kind);
            assertThat(c.payload()).isEqualTo("payload-" + kind);
        }
        assertThat(PayCredential.Kind.values()).hasSize(6);
    }

    @Test
    @DisplayName("kind 与 payload 必填（null 即构造失败，防「空凭证」静默穿过）")
    void kindAndPayloadAreRequired() {
        assertThatThrownBy(() -> new PayCredential(null, "x", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> new PayCredential(PayCredential.Kind.REDIRECT_URL, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("expiresAt 可空（并非所有渠道都给有效期）")
    void expiresAtMayBeNull() {
        Instant exp = Instant.parse("2026-09-20T12:00:00Z");
        assertThat(PayCredential.redirectUrl("https://x", exp).expiresAt()).isEqualTo(exp);
        assertThat(PayCredential.redirectUrl("https://x", null).expiresAt()).isNull();
    }
}
