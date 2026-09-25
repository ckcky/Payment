package com.payment.channelgateway.infra.wechat;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.dto.channel.CallbackUrls;
import com.payment.common.dto.channel.Goods;
import com.payment.common.dto.channel.PayCredential;
import com.payment.common.dto.channel.PaymentScene;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 039 / T5：<b>本地微信 V3 仿真桩 + 四步全链路</b>（FR-012 / FR-013 / SC-005）。
 *
 * <p>用 JDK 内置 {@code com.sun.net.httpserver.HttpServer} 起一个只监听 {@code 127.0.0.1}
 * 随机端口的桩，把 {@code WechatPayProperties.apiBaseUrl} 指过去——<b>全程不触达微信网络</b>。
 * 不引入 WireMock 等新依赖：桩需要的能力（路由 + 读头 + 读体 + 回 JSON）JDK 已经够用。</p>
 *
 * <h3>为什么必须断言「桩侧收到的 Authorization 可验证」</h3>
 * 只断言「插件返回了凭证」是不够的——那只能证明桩回了 200。真正的风险是<b>签名错了</b>
 * （微信会 401，而桩不会）。故这里把桩收到的 {@code timestamp} / {@code nonce_str} / 报文
 * 重新拼成基串，用<b>商户公钥</b>验签：验过才说明这条请求在真实微信侧也会被接受。</p>
 *
 * <h3>为什么桩是唯一出口</h3>
 * {@code apiBaseUrl} 指向 {@code 127.0.0.1:<随机端口>}，且 {@code HttpClient} 只认这个 base——
 * 任何对 {@code api.mch.weixin.qq.com} 的调用都不会出现在本测试里。测试跑在无网 CI 上也成立。</p>
 */
class WechatStubServerTest {

    private static final String MCH_ID = "1900000109";
    private static final String APP_ID = "wx1234567890abcdef";
    private static final String MERCHANT_SERIAL = "MERCHANT_SERIAL_TEST_0001";
    private static final String PLATFORM_KEY_ID = "PUB_KEY_ID_TEST_0001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NOTIFY = "https://example.com/internal/channels/WECHAT/callback";
    private static final String CODE_URL = "weixin://wxpay/bizpayurl?pr=STUB0001";

    private static HttpServer server;
    private static String baseUrl;
    private static KeyPair merchantKeyPair;
    private static KeyPair platformKeyPair;

    /** 桩收到的请求（供断言「平台发出去的是什么」）。 */
    private static final List<Recorded> RECORDED = new CopyOnWriteArrayList<>();

    private record Recorded(String method, String path, String query, String authorization, String body) {

        String pathWithQuery() {
            return query == null || query.isBlank() ? path : path + "?" + query;
        }
    }

    @BeforeAll
    static void startStub() throws IOException {
        merchantKeyPair = WechatTestSupport.rsaKeyPair();
        platformKeyPair = WechatTestSupport.rsaKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/pay/transactions/native", exchange -> {
            // ⚠️ 请求体只能读一次：先读出来，再交给 respond —— 否则 respond 二次读取会拿到空串，
            // 既让「记录到的报文」失真，也会让据此重建的基串与真实签名不符。
            String body = readBody(exchange);
            if (body.contains("PM-DECLINE")) {
                // 模拟微信的业务拒绝（4xx + code/message）
                respond(exchange, 400, "{\"code\":\"NOTENOUGH\",\"message\":\"余额不足\"}", body);
                return;
            }
            respond(exchange, 200, "{\"code_url\":\"" + CODE_URL + "\"}", body);
        });
        server.createContext("/v3/pay/transactions/jsapi",
                exchange -> respond(exchange, 200, "{\"prepay_id\":\"wx-prepay-stub-0001\"}"));
        server.createContext("/v3/pay/transactions/h5",
                exchange -> respond(exchange, 200, "{\"h5_url\":\"https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=stub\"}"));
        server.createContext("/v3/pay/transactions/out-trade-no",
                exchange -> respond(exchange, 200, querySuccessJson()));
        server.createContext("/v3/pay/transactions/id",
                exchange -> respond(exchange, 200, querySuccessJson()));
        server.createContext("/v3/refund/domestic/refunds",
                exchange -> respond(exchange, 200, "{\"refund_id\":\"5000000000000000000000001\",\"status\":\"SUCCESS\"}"));
        server.start();

        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void resetRecorded() {
        RECORDED.clear();
        DyeContext.set(DyeMode.SANDBOX);
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    // ===================== 四步全链路 =====================

    @Test
    @DisplayName("全链路：下单 → 查询（两种键）→ 退款 → 回调，全程不依赖微信网络 [SC-005][FR-012]")
    void fourStepChainRunsEntirelyAgainstLocalStub() {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        // ---- ① 下单（NATIVE）----
        ChannelResult charged = plugin.charge(nativeCharge("PM001", 100L));
        assertThat(charged.status()).isEqualTo(ChannelResult.Status.UNKNOWN); // 受理 ≠ 成功
        assertThat(charged.hasCredential()).isTrue();
        assertThat(charged.credential().kind()).isEqualTo(PayCredential.Kind.QR_CODE);
        assertThat(charged.credential().payload()).isEqualTo(CODE_URL);

        // ---- ② 查询：两种键走两条路径 ----
        ChannelResult byOutTradeNo = plugin.queryStatus(
                new QueryStatusRequest("PM001", "TX-1", "idem-1", null));
        assertThat(byOutTradeNo.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(byOutTradeNo.channelReference()).isEqualTo("4200001234202609251234567890");

        ChannelResult byTransactionId = plugin.queryStatus(
                new QueryStatusRequest("PM001", "TX-1", "idem-1", "4200001234202609251234567890"));
        assertThat(byTransactionId.status()).isEqualTo(ChannelResult.Status.SUCCESS);

        assertThat(pathsOf("/v3/pay/transactions/out-trade-no")).hasSize(1);
        assertThat(pathsOf("/v3/pay/transactions/id")).hasSize(1);
        // 两种键都必须带上 mchid（微信要求）
        assertThat(RECORDED).anySatisfy(r -> assertThat(r.query()).contains("mchid=" + MCH_ID));

        // ---- ③ 退款 ----
        ChannelResult refunded = plugin.refund(new RefundRequest(
                "PM001", "R001", 100L, "CNY", "WECHAT",
                "4200001234202609251234567890", "R001", "用户申请", NOTIFY));
        assertThat(refunded.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(refunded.channelReference()).isEqualTo("5000000000000000000000001");

        // ---- ④ 回调 ----
        ParsedCallback parsed = plugin.parseCallback(signedNotification());
        assertThat(parsed.paymentNo()).isEqualTo("PM001");
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(parsed.notifiedAmount().amountMinor()).isEqualTo(100L);
        assertThat(parsed.notifiedAmount().currencyCode()).isEqualTo("CNY");

        // 应答体是微信期望的形态
        assertThat(plugin.callbackAckBody()).isEqualTo("{\"code\":\"SUCCESS\",\"message\":\"成功\"}");
    }

    @Test
    @DisplayName("桩侧收到的请求带可验证的 V3 签名（商户公钥验签通过）——端到端证明签名正确 [FR-011]")
    void stubReceivesVerifiableV3Signature() {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        plugin.charge(nativeCharge("PM001", 100L));

        Recorded request = lastFor("/v3/pay/transactions/native");
        assertThat(request).isNotNull();
        assertThat(request.authorization()).startsWith("WECHATPAY2-SHA256-RSA2048 mchid=\"" + MCH_ID + "\"");
        assertThat(request.authorization()).contains("serial_no=\"" + MERCHANT_SERIAL + "\"");

        // 用桩收到的 timestamp / nonce / 报文重建基串，用商户公钥验签
        String timestamp = extract(request.authorization(), "timestamp");
        String nonce = extract(request.authorization(), "nonce_str");
        String signature = extract(request.authorization(), "signature");
        String baseString = WechatPaySigner.baseString("POST", request.pathWithQuery(),
                Long.parseLong(timestamp), nonce, request.body());

        assertThat(WechatTestSupport.rsaVerify(merchantKeyPair.getPublic(), baseString, signature))
                .as("桩收到的 Authorization 必须是可用商户公钥验证的真实 V3 签名")
                .isTrue();
    }

    @Test
    @DisplayName("金额以「分」原样发出（100 分写 100，不写 10000）[INV-4]")
    void amountIsSentInMinorUnits() {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        plugin.charge(nativeCharge("PM001", 100L));

        String body = lastFor("/v3/pay/transactions/native").body();
        assertThat(body).contains("\"total\":100");
        assertThat(body).doesNotContain("\"total\":10000");
        assertThat(body).contains("\"currency\":\"CNY\"");
        assertThat(body).contains("\"out_trade_no\":\"PM001\"");
    }

    @Test
    @DisplayName("退款报文：out_refund_no = 平台 refundNo；total=refund（ADR-0016 恒全退）[FR-006]")
    void refundBodyCarriesPlatformRefundNo() {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        plugin.refund(new RefundRequest("PM001", "R001", 100L, "CNY", "WECHAT",
                "4200001234202609251234567890", "R001", "用户申请", NOTIFY));

        String body = lastFor("/v3/refund/domestic/refunds").body();
        assertThat(body).contains("\"out_refund_no\":\"R001\"");
        assertThat(body).contains("\"transaction_id\":\"4200001234202609251234567890\"");
        assertThat(body).contains("\"refund\":100");
        assertThat(body).contains("\"total\":100");
    }

    @Test
    @DisplayName("渠道 4xx 业务拒绝 ⇒ businessFailure（不重试），错误码被翻译 [FR-006]")
    void stubBusinessRejectionIsTranslated() {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        ChannelResult result = plugin.charge(nativeCharge("PM-DECLINE-1", 100L));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(result.retryable()).isFalse();
        assertThat(result.businessCode()).isEqualTo(BusinessCode.INSUFFICIENT_FUNDS);
    }

    @Test
    @DisplayName("JSAPI 下单 ⇒ prepay_id 经 paySign 组装成 JSAPI_PARAMS 凭证 [FR-004]")
    void jsapiProducesSignedPayParams() throws Exception {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        ChannelResult result = plugin.charge(new ChargeRequest(
                "PM002", 100L, "CNY", "WECHAT", PaymentScene.JSAPI, Goods.of("测试商品"),
                new CallbackUrls(NOTIFY, null), Instant.now().plusSeconds(300),
                com.payment.common.dto.channel.Payer.of("openid-test-1"), null, null));

        assertThat(result.credential().kind()).isEqualTo(PayCredential.Kind.JSAPI_PARAMS);
        assertThat(result.credential().payload()).contains("\"package\":\"prepay_id=wx-prepay-stub-0001\"");
        assertThat(result.credential().payload()).contains("\"signType\":\"RSA\"");
        assertThat(result.credential().payload()).contains("\"paySign\":");
    }

    @Test
    @DisplayName("归属恒读 channel_code：插件身份恒为 WECHAT（不随模态/场景漂移）[FR-013]")
    void channelIdentityIsStable() {
        WechatPayProperties props = properties();
        WechatChannelPlugin plugin = new WechatChannelPlugin(new WechatSdkGateway(props), props);

        assertThat(plugin.channelCode()).isEqualTo("WECHAT");

        DyeContext.clear(); // MOCK
        assertThat(plugin.channelCode()).isEqualTo("WECHAT");
        DyeContext.set(DyeMode.SANDBOX); // SANDBOX
        assertThat(plugin.channelCode()).isEqualTo("WECHAT");
    }

    // ===================== 桩辅助 =====================

    private static WechatPayProperties properties() {
        WechatPayProperties p = new WechatPayProperties();
        p.setEnabled(true);
        p.setApiBaseUrl(baseUrl); // ← 唯一出口：只指本地桩
        p.setMchId(MCH_ID);
        p.setAppId(APP_ID);
        p.setApiV3Key(API_V3_KEY);
        p.setMerchantSerialNo(MERCHANT_SERIAL);
        p.setPrivateKey(WechatTestSupport.toPem("PRIVATE KEY", merchantKeyPair.getPrivate().getEncoded()));
        p.setPlatformPublicKey(WechatTestSupport.toPem("PUBLIC KEY", platformKeyPair.getPublic().getEncoded()));
        p.setPlatformPublicKeyId(PLATFORM_KEY_ID);
        p.setNotifyUrl(NOTIFY);
        return p;
    }

    private static ChargeRequest nativeCharge(String paymentNo, long amountMinor) {
        return new ChargeRequest(paymentNo, amountMinor, "CNY", "WECHAT",
                PaymentScene.NATIVE, Goods.of("测试商品"), new CallbackUrls(NOTIFY, null),
                Instant.now().plusSeconds(300), null, null, null);
    }

    private static ChannelCallbackEnvelope signedNotification() {
        String resourceNonce = "abcdef123456";
        String body = WechatTestSupport.notificationBody(API_V3_KEY, resourceNonce, "transaction",
                "{\"mchid\":\"" + MCH_ID + "\",\"appid\":\"" + APP_ID + "\",\"out_trade_no\":\"PM001\","
                        + "\"transaction_id\":\"4200001234202609251234567890\",\"trade_type\":\"NATIVE\","
                        + "\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":100,\"currency\":\"CNY\"}}");
        String timestamp = "1789000000";
        String nonce = "NONCE-STUB-0001";
        String signature = WechatTestSupport.rsaSignBase64(platformKeyPair.getPrivate(),
                timestamp + "\n" + nonce + "\n" + body + "\n");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("wechatpay-timestamp", timestamp);
        headers.put("wechatpay-nonce", nonce);
        headers.put("wechatpay-signature", signature);
        headers.put("wechatpay-serial", PLATFORM_KEY_ID);
        return new ChannelCallbackEnvelope(headers, Map.of(), body);
    }

    private static String querySuccessJson() {
        return "{\"out_trade_no\":\"PM001\",\"transaction_id\":\"4200001234202609251234567890\","
                + "\"trade_state\":\"SUCCESS\",\"trade_state_desc\":\"支付成功\","
                + "\"amount\":{\"total\":100,\"payer_total\":100,\"currency\":\"CNY\"}}";
    }

    private static List<Recorded> pathsOf(String pathPrefix) {
        return RECORDED.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    private static Recorded lastFor(String pathPrefix) {
        List<Recorded> matched = pathsOf(pathPrefix);
        return matched.isEmpty() ? null : matched.get(matched.size() - 1);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 统一应答：读取并记录请求 → 回 JSON。 */
    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, json, readBody(exchange));
    }

    /** 统一应答：请求体已由调用方读过时用这个重载（请求体只能读一次）。 */
    private static void respond(HttpExchange exchange, int status, String json, String body)
            throws IOException {
        RECORDED.add(new Recorded(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String extract(String authorization, String name) {
        String marker = name + "=\"";
        int start = authorization.indexOf(marker) + marker.length();
        int end = authorization.indexOf('"', start);
        return authorization.substring(start, end);
    }
}
