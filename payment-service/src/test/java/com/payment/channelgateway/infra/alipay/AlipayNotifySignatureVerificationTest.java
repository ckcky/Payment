package com.payment.channelgateway.infra.alipay;

import com.payment.channelgateway.infra.config.AlipaySandboxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝异步通知验签口径回归（spec 030 / FR-202 / INV-10）。
 *
 * <p><b>为什么单独有这个测试类</b>：既有所有 notify 测试都把
 * {@code verifyNotify} **stub 掉**（返回固定 true/false），于是「真报文 + 真验签」
 * 这条路径从未被任何自动化测试覆盖过——2026-09-20 沙箱联调时它整条挂了。</p>
 *
 * <p><b>抓到的真缺陷</b>：{@code AlipaySignature.rsaCheckV2} 在本 SDK 版本
 * （alipay-sdk-java 4.40.996.ALL）里的待签串**保留了 {@code sign_type}**
 * （{@code getSignCheckContentV2} 只 remove {@code sign}），而支付宝给异步通知签名时
 * **不含 {@code sign_type}** ⇒ 待签串多一段 {@code sign_type=RSA2} ⇒
 * <b>真实回调 100% 验签失败</b>：notify 全部 403、支付单永远收敛不了。
 * 真报文实测：V1 待签串 597 字符通过，V2 待签串 612 字符失败（同一把公钥、同一条通知）。</p>
 *
 * <p>本类用**自签密钥对**复现该口径，不依赖任何沙箱密钥/网络，可在 CI 常态运行。
 * 两个测试互为照应：</p>
 * <ul>
 *   <li>{@link #realShapedNotifyMustVerify()}：按支付宝规范签名（剔除 sign + sign_type）⇒ 必须通过。
 *       改回 {@code rsaCheckV2} 时本断言立刻红。</li>
 *   <li>{@link #signTypeMustBeExcludedFromSignedContent()}：<b>阳性对照</b>——
 *       故意让 {@code sign_type} 参与签名 ⇒ 必须判为不通过。
 *       没有这条，将来有人把验签"修"成 V2 语义时，上一条会被顺手改绿而无人察觉。</li>
 * </ul>
 */
class AlipayNotifySignatureVerificationTest {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;

    @Test
    @DisplayName("真实形态的支付宝异步通知（sign_type 不参与签名）必须验签通过")
    void realShapedNotifyMustVerify() throws Exception {
        KeyPair keyPair = newKeyPair();
        Map<String, String> params = realShapedNotify();
        params.put("sign", sign(canonicalContent(params, false), keyPair));

        AlipaySdkGateway gateway = gatewayWith(keyPair);

        assertThat(gateway.verifyNotify(params))
                .as("按支付宝规范签名（剔除 sign 与 sign_type）必须通过；"
                        + "若有人改回 rsaCheckV2，此处会失败——这正是 2026-09-20 沙箱实测抓到的缺陷")
                .isTrue();

        // 验签不得就地改写调用方的 params（SDK 的 getSignCheckContent* 会 remove）——
        // 控制器验签后还要继续读同一个 map 里的 out_trade_no / trade_status 等字段。
        assertThat(params).containsKey("sign").containsKey("sign_type").containsKey("out_trade_no");
    }

    @Test
    @DisplayName("阳性对照：让 sign_type 参与签名必须验签失败")
    void signTypeMustBeExcludedFromSignedContent() throws Exception {
        KeyPair keyPair = newKeyPair();
        Map<String, String> params = realShapedNotify();
        // 刻意按「sign_type 也算进待签串」的口径签名（= SDK rsaCheckV2 的语义）
        params.put("sign", sign(canonicalContent(params, true), keyPair));

        AlipaySdkGateway gateway = gatewayWith(keyPair);

        assertThat(gateway.verifyNotify(params))
                .as("sign_type 参与签名与支付宝口径不符，必须判为不通过；"
                        + "本断言若变绿，说明验签又退回了 rsaCheckV2 的语义")
                .isFalse();
    }

    @Test
    @DisplayName("空报文 / 缺 sign 一律不通过（绝不因异常放行，INV-10）")
    void missingSignatureMustNotPass() throws Exception {
        AlipaySdkGateway gateway = gatewayWith(newKeyPair());

        assertThat(gateway.verifyNotify(new LinkedHashMap<>())).isFalse();
        assertThat(gateway.verifyNotify(realShapedNotify())).isFalse();
    }

    // ---- 夹具 ----

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE);
        return generator.generateKeyPair();
    }

    private static AlipaySdkGateway gatewayWith(KeyPair keyPair) throws Exception {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setGatewayUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        properties.setAppId("9021000168642666");
        properties.setAppPrivateKey(base64(keyPair.getPrivate().getEncoded()));
        properties.setAlipayPublicKey(base64(keyPair.getPublic().getEncoded()));
        return new AlipaySdkGateway(properties);
    }

    /**
     * 支付宝规范待签串：剔除 {@code sign}（与 {@code sign_type}）、空值不参与、
     * 按 key 字典序、{@code &} 连接、无尾随 {@code &}。
     *
     * @param keepSignType {@code true} 表示刻意复现「sign_type 参与签名」的错误口径（阳性对照用）
     */
    private static String canonicalContent(Map<String, String> params, boolean keepSignType) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if ("sign".equals(key)) {
                return;
            }
            if (!keepSignType && "sign_type".equals(key)) {
                return;
            }
            if (value == null || value.isEmpty()) {
                return;
            }
            sorted.put(key, value);
        });
        StringBuilder content = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (content.length() > 0) {
                content.append('&');
            }
            content.append(key).append('=').append(value);
        });
        return content.toString();
    }

    private static String sign(String content, KeyPair keyPair) throws Exception {
        Signature signature = Signature.getInstance(SIGN_ALGORITHM);
        signature.initSign(keyPair.getPrivate());
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return base64(signature.sign());
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 一条形状贴近真实支付宝异步通知的报文：含带空格的日期、含 JSON 引号的
     * {@code fund_bill_list}、含 {@code sign_type}——这些正是待签串口径最容易出错的地方。
     * 字段与取值取自 2026-09-20 沙箱真实 {@code TRADE_SUCCESS} 通知。
     */
    private static Map<String, String> realShapedNotify() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gmt_create", "2026-09-20 19:12:49");
        params.put("charset", "UTF-8");
        params.put("gmt_payment", "2026-09-20 19:13:17");
        params.put("notify_time", "2026-09-20 19:13:19");
        params.put("subject", "PM227385792291659776");
        params.put("buyer_id", "2088722113129286");
        params.put("invoice_amount", "99.00");
        params.put("version", "1.0");
        params.put("notify_id", "2026092001222191318129280508562601");
        params.put("fund_bill_list", "[{\"amount\":\"99.00\",\"fundChannel\":\"ALIPAYACCOUNT\"}]");
        params.put("notify_type", "trade_status_sync");
        params.put("out_trade_no", "PM227385792291659776");
        params.put("total_amount", "99.00");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("trade_no", "2026092022001429280508654227");
        params.put("auth_app_id", "9021000168642666");
        params.put("receipt_amount", "99.00");
        params.put("point_amount", "0.00");
        params.put("buyer_pay_amount", "99.00");
        params.put("app_id", "9021000168642666");
        params.put("sign_type", "RSA2");
        params.put("seller_id", "2088721113106792");
        return params;
    }
}
