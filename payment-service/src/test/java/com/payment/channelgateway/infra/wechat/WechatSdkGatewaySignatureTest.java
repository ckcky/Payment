package com.payment.channelgateway.infra.wechat;

import com.wechat.pay.java.core.auth.WechatPay2Credential;
import com.wechat.pay.java.core.cipher.RSASigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 039 / T2：微信 V3 请求签名<b>金标准</b>（FR-011 / INV-4）。
 *
 * <h3>为什么签名串不是写死的字面量</h3>
 * 金标准的本意是「固定输入 ⇒ 固定输出」，而 RSA 签名要求<b>固定私钥</b>。
 * 本仓库纪律（FR-009 / INV-1）<b>禁止</b>任何私钥进 git，故测试密钥对在运行时生成——
 * 于是签名值本身必然每次不同。这里用<b>等价强度</b>的三层断言替代：
 * <ol>
 *   <li><b>基串逐字节字面量</b>——把微信 V3 的「五行 + 每行 {@code \n}」格式钉死，
 *       格式一旦被改动立即红；</li>
 *   <li><b>签名可验证</b>——用公钥 {@code SHA256withRSA} 验证，证明签名确实是
 *       「用对应私钥、对<b>该基串</b>」签出来的（不是别的串）；</li>
 *   <li><b>与 SDK 交叉校验</b>——取 SDK {@code WechatPay2Credential} 产出的
 *       {@code timestamp} / {@code nonce_str}，用<b>我们的</b>基串重建并重签，
 *       签名必须与 SDK 的<b>逐字节相同</b>（PKCS#1 v1.5 是确定性签名）。
 *       这是「我们的格式 == 官方 SDK 的格式」的机器可验证证据。</li>
 * </ol>
 */
class WechatSdkGatewaySignatureTest {

    private static final String MERCHANT_ID = "1900000109";
    private static final String SERIAL_NO = "5157F09EFDC096DE15EBE81A47057A72A";
    private static final String NONCE = "593BEC0C930BF1AFEB40B4A08C8FB242";
    private static final long TIMESTAMP = 1554208460L;
    private static final String BODY = "{\"out_trade_no\":\"PM001\"}";
    private static final String PATH_NATIVE = "/v3/pay/transactions/native";
    private static final String PATH_QUERY =
            "/v3/pay/transactions/out-trade-no/PM001?mchid=1900000109";

    private static KeyPair keyPair;

    @BeforeAll
    static void generateTestKeyPair() throws Exception {
        // 运行时生成：私钥绝不进 git（FR-009 / INV-1）
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static WechatPaySigner signer() {
        return new WechatPaySigner(MERCHANT_ID, SERIAL_NO, keyPair.getPrivate());
    }

    // ---- ① 基串格式金标准（逐字节字面量） ----

    @Test
    @DisplayName("基串 = 方法\\nURL\\n时间戳\\n随机串\\n报文\\n（POST 带体）[FR-011]")
    void baseStringForPostWithBody() {
        String base = WechatPaySigner.baseString("POST", PATH_NATIVE, TIMESTAMP, NONCE, BODY);

        assertThat(base).isEqualTo(
                "POST\n"
                        + "/v3/pay/transactions/native\n"
                        + "1554208460\n"
                        + "593BEC0C930BF1AFEB40B4A08C8FB242\n"
                        + "{\"out_trade_no\":\"PM001\"}\n");
    }

    @Test
    @DisplayName("基串：GET 无体 ⇒ 第 5 行为空行；query 参与签名 [FR-005][FR-011]")
    void baseStringForGetHasEmptyBodyLine() {
        String base = WechatPaySigner.baseString("GET", PATH_QUERY, TIMESTAMP, NONCE, null);

        assertThat(base).isEqualTo(
                "GET\n"
                        + "/v3/pay/transactions/out-trade-no/PM001?mchid=1900000109\n"
                        + "1554208460\n"
                        + "593BEC0C930BF1AFEB40B4A08C8FB242\n"
                        + "\n");
        // null 与 "" 等价（无体）
        assertThat(base).isEqualTo(WechatPaySigner.baseString("GET", PATH_QUERY, TIMESTAMP, NONCE, ""));
    }

    // ---- ② Authorization 头结构 + 签名可验证 ----

    @Test
    @DisplayName("Authorization 头：schema/mchid/nonce_str/signature/timestamp/serial_no 顺序与引号 [FR-011]")
    void authorizationHeaderShape() {
        String header = signer().authorization("POST", PATH_NATIVE, BODY, TIMESTAMP, NONCE);

        assertThat(header).startsWith(
                "WECHATPAY2-SHA256-RSA2048 mchid=\"1900000109\",nonce_str=\"" + NONCE + "\",signature=\"");
        assertThat(header).endsWith(",timestamp=\"1554208460\",serial_no=\"" + SERIAL_NO + "\"");
    }

    @Test
    @DisplayName("签名 = Base64(RSA-SHA256(基串))，用公钥可验证通过 [FR-011]")
    void signatureVerifiesAgainstBaseString() throws Exception {
        String header = signer().authorization("POST", PATH_NATIVE, BODY, TIMESTAMP, NONCE);
        String signature = extract(header, "signature");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(WechatPaySigner.baseString("POST", PATH_NATIVE, TIMESTAMP, NONCE, BODY)
                .getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(Base64.getDecoder().decode(signature))).isTrue();
    }

    // ---- ③ 与官方 SDK 交叉校验（格式等价的机器可验证证据） ----

    @Test
    @DisplayName("交叉校验：我们的基串格式与官方 SDK 逐字节等价（POST 带体）[FR-011]")
    void baseStringMatchesOfficialSdkForPost() {
        assertBaseStringMatchesSdk("POST", PATH_NATIVE, BODY);
    }

    @Test
    @DisplayName("交叉校验：我们的基串格式与官方 SDK 逐字节等价（GET 带 query、无体）[FR-011]")
    void baseStringMatchesOfficialSdkForGetWithQuery() {
        assertBaseStringMatchesSdk("GET", PATH_QUERY, "");
    }

    /**
     * 取 SDK 产出的 timestamp / nonce，用<b>我们的</b>基串重签，签名必须与 SDK 的一致。
     *
     * <p>PKCS#1 v1.5 是确定性签名：同一私钥 + 同一消息 ⇒ 同一签名。故签名相等
     * ⟺ 两次签的消息（基串）逐字节相等。</p>
     */
    private static void assertBaseStringMatchesSdk(String method, String path, String body) {
        WechatPay2Credential sdkCredential =
                new WechatPay2Credential(MERCHANT_ID, new RSASigner(SERIAL_NO, keyPair.getPrivate()));
        String sdkHeader = sdkCredential.getAuthorization(
                URI.create("https://api.mch.weixin.qq.com" + path), method, body);

        long sdkTimestamp = Long.parseLong(extract(sdkHeader, "timestamp"));
        String sdkNonce = extract(sdkHeader, "nonce_str");

        String ourHeader = signer().authorization(method, path, body, sdkTimestamp, sdkNonce);

        assertThat(extract(ourHeader, "signature"))
                .as("我们的基串格式必须与官方 SDK 逐字节一致（否则重签结果不同）")
                .isEqualTo(extract(sdkHeader, "signature"));
    }

    private static String extract(String header, String name) {
        String marker = name + "=\"";
        int start = header.indexOf(marker) + marker.length();
        int end = header.indexOf('"', start);
        return header.substring(start, end);
    }
}
