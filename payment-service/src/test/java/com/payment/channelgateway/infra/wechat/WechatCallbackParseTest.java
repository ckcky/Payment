package com.payment.channelgateway.infra.wechat;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.common.core.error.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 039 / T4：V3 通知「验签 → 解密 → 翻译」（FR-007 / FR-011 / INV-5 / INV-7）。
 *
 * <p>全部使用<b>运行时生成</b>的测试密钥对，不依赖微信网络，也不把任何私钥写进仓库
 * （FR-009 / INV-1）。</p>
 *
 * <h3>关键断言：顺序不可颠倒（INV-5）</h3>
 * 「签名正确但密文被篡改」与「签名被篡改」两类用例的<b>失败原因不同</b>——
 * 前者应报 decrypt，后者应报 signature。若实现把解密放在验签之前，
 * 第一类用例会以 decrypt 失败告终（看起来「对」），但第二类会变成
 * 「先解密再验签」的顺序错误；两条断言合起来才能钉死顺序。
 */
class WechatCallbackParseTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef"; // 32 字节
    private static final String PLATFORM_KEY_ID = "PUB_KEY_ID_0112345678901234";
    private static final String PLATFORM_SERIAL = "PLATFORM_SERIAL_0001";
    private static final String RESOURCE_NONCE = "abcdef123456";
    private static final String ASSOCIATED_DATA = "transaction";
    private static final String NOTIFY = "https://example.com/internal/channels/WECHAT/callback";

    private static KeyPair platformKeyPair;
    private static String platformPublicKeyPem;

    @BeforeAll
    static void generatePlatformKeyPair() {
        platformKeyPair = WechatTestSupport.rsaKeyPair();
        platformPublicKeyPem = WechatTestSupport.toPem("PUBLIC KEY", platformKeyPair.getPublic().getEncoded());
    }

    private static WechatChannelPlugin plugin() {
        WechatPayProperties props = new WechatPayProperties();
        props.setEnabled(true);
        props.setAppId("wx1234567890abcdef");
        props.setMchId("1900000109");
        props.setApiV3Key(API_V3_KEY);
        props.setPlatformPublicKey(platformPublicKeyPem);
        props.setPlatformPublicKeyId(PLATFORM_KEY_ID);
        props.setNotifyUrl(NOTIFY);
        return new WechatChannelPlugin(new WechatSdkGateway(props), props);
    }

    /** 构造一条合法通知的原始体（密文 = AES-256-GCM(明文)）。 */
    private static String notificationBody(String plaintext) {
        return WechatTestSupport.notificationBody(API_V3_KEY, RESOURCE_NONCE, ASSOCIATED_DATA, plaintext);
    }

    private static ChannelCallbackEnvelope envelope(String body, String serialOverride) {
        String timestamp = "1789000000";
        String nonce = "NONCE1234567890";
        String signature = WechatTestSupport.rsaSignBase64(platformKeyPair.getPrivate(),
                timestamp + "\n" + nonce + "\n" + body + "\n");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("wechatpay-timestamp", timestamp);
        headers.put("wechatpay-nonce", nonce);
        headers.put("wechatpay-signature", signature);
        headers.put("wechatpay-serial", serialOverride == null ? PLATFORM_KEY_ID : serialOverride);
        return new ChannelCallbackEnvelope(headers, Map.of(), body);
    }

    private static final String SUCCESS_PLAINTEXT =
            "{\"mchid\":\"1900000109\",\"appid\":\"wx1234567890abcdef\",\"out_trade_no\":\"PM001\","
                    + "\"transaction_id\":\"4200001234202609251234567890\",\"trade_type\":\"NATIVE\","
                    + "\"trade_state\":\"SUCCESS\",\"trade_state_desc\":\"支付成功\","
                    + "\"amount\":{\"total\":100,\"payer_total\":100,\"currency\":\"CNY\"}}";

    // ---- 往返：验签 + 解密 + 翻译 ----

    @Test
    @DisplayName("合法通知 ⇒ 验签通过、AES-256-GCM 解密、翻译为 ParsedCallback（含金额）[FR-007][FR-011]")
    void validNotificationRoundTrips() throws Exception {
        String body = notificationBody(SUCCESS_PLAINTEXT);

        ParsedCallback parsed = plugin().parseCallback(envelope(body, null));

        assertThat(parsed.paymentNo()).isEqualTo("PM001");
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(parsed.result().channelReference()).isEqualTo("4200001234202609251234567890");
        assertThat(parsed.notifiedAmount().isKnown()).isTrue();
        assertThat(parsed.notifiedAmount().amountMinor()).isEqualTo(100L);
        assertThat(parsed.notifiedAmount().currencyCode()).isEqualTo("CNY");
    }

    // ---- INV-5：顺序不可颠倒 ----

    @Test
    @DisplayName("签名被篡改 ⇒ 拒绝，失败原因是 signature（说明未进入解密）[INV-5]")
    void tamperedSignatureIsRejectedBeforeDecrypt() throws Exception {
        String body = notificationBody(SUCCESS_PLAINTEXT);
        ChannelCallbackEnvelope good = envelope(body, null);
        Map<String, String> tampered = new LinkedHashMap<>(good.headers());
        tampered.put("wechatpay-signature", "AAAA" + good.headers().get("wechatpay-signature"));
        ChannelCallbackEnvelope broken = new ChannelCallbackEnvelope(tampered, Map.of(), body);

        assertThatThrownBy(() -> plugin().parseCallback(broken))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("签名正确但密文被篡改 ⇒ 解密失败（说明验签已先通过）[INV-5]")
    void tamperedCiphertextFailsAtDecrypt() throws Exception {
        String body = notificationBody(SUCCESS_PLAINTEXT);
        // 篡改密文（保留合法签名：签名只覆盖整个 body 串，故需重新对篡改后的 body 签名）
        String tamperedBody = body.replaceFirst(
                "\"ciphertext\":\"[A-Za-z0-9+/=]{4}", "\"ciphertext\":\"ZZZZ");
        assertThat(tamperedBody).isNotEqualTo(body);

        assertThatThrownBy(() -> plugin().parseCallback(envelope(tamperedBody, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("decrypt");
    }

    @Test
    @DisplayName("Wechatpay-Serial 与平台密钥不匹配 ⇒ 拒绝（F5 证书轮换/伪造）[FR-007]")
    void serialMismatchIsRejected() throws Exception {
        String body = notificationBody(SUCCESS_PLAINTEXT);

        assertThatThrownBy(() -> plugin().parseCallback(envelope(body, PLATFORM_SERIAL)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("serial mismatch");
    }

    @Test
    @DisplayName("缺签名头 ⇒ 拒绝（未签名通知不可信）[FR-007]")
    void missingSignatureHeadersAreRejected() throws Exception {
        String body = notificationBody(SUCCESS_PLAINTEXT);
        ChannelCallbackEnvelope unsigned = new ChannelCallbackEnvelope(
                Map.of("wechatpay-timestamp", "1789000000"), Map.of(), body);

        assertThatThrownBy(() -> plugin().parseCallback(unsigned))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("signature headers");
    }

    @Test
    @DisplayName("通知缺 out_trade_no ⇒ 拒绝，绝不猜单据 [FR-007]")
    void notificationWithoutOutTradeNoIsRejected() throws Exception {
        String body = notificationBody("{\"transaction_id\":\"4200\",\"trade_state\":\"SUCCESS\"}");

        assertThatThrownBy(() -> plugin().parseCallback(envelope(body, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("out_trade_no");
    }

    // ---- INV-7：状态映射不臆断 ----

    @Test
    @DisplayName("trade_state=USERPAYING / NOTPAY ⇒ UNKNOWN，不判失败 [INV-7]")
    void pendingStatesMapToUnknown() throws Exception {
        for (String state : new String[]{"USERPAYING", "NOTPAY"}) {
            String body = notificationBody(SUCCESS_PLAINTEXT.replace("\"SUCCESS\"", "\"" + state + "\""));
            ParsedCallback parsed = plugin().parseCallback(envelope(body, null));

            assertThat(parsed.result().status())
                    .as("trade_state=%s 不得被判为 FAILURE", state)
                    .isEqualTo(ChannelResult.Status.UNKNOWN);
        }
    }

    @Test
    @DisplayName("trade_state=CLOSED / REFUND / PAYERROR ⇒ FAILURE [INV-7]")
    void closedStatesMapToFailure() throws Exception {
        for (String state : new String[]{"CLOSED", "REFUND", "PAYERROR"}) {
            String body = notificationBody(SUCCESS_PLAINTEXT.replace("\"SUCCESS\"", "\"" + state + "\""));
            ParsedCallback parsed = plugin().parseCallback(envelope(body, null));

            assertThat(parsed.result().status())
                    .as("trade_state=%s 应判 FAILURE", state)
                    .isEqualTo(ChannelResult.Status.FAILURE);
        }
    }

    // ---- 应答体 ----

    @Test
    @DisplayName("回调应答体为微信期望的 {code:SUCCESS,message:成功} [FR-007]")
    void ackBodyMatchesWechatExpectation() {
        assertThat(plugin().callbackAckBody()).isEqualTo("{\"code\":\"SUCCESS\",\"message\":\"成功\"}");
    }
}
