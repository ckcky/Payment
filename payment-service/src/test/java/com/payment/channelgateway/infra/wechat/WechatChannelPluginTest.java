package com.payment.channelgateway.infra.wechat;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.dto.channel.CallbackUrls;
import com.payment.common.dto.channel.Goods;
import com.payment.common.dto.channel.PayCredential;
import com.payment.common.dto.channel.Payer;
import com.payment.common.dto.channel.PaymentScene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 039 / T3：微信插件身份、能力声明与双模态分派（FR-003 / FR-010 / FR-015 / FR-016 / INV-8）。
 *
 * <p>核心是四组断言：
 * <ol>
 *   <li><b>描述符自描述</b>：code / 场景集合 / 回调路径（FR-003）；</li>
 *   <li><b>C-1 裁决</b>：{@code enabled=false} 时插件<b>仍然注册</b>且 MOCK 照常可用，
 *       只有染色 SANDBOX 才 400（FR-016 / FR-241）；</li>
 *   <li><b>C-3 裁决</b>：工厂只注册 Spring Bean，无任何条件装配注解（FR-015）；</li>
 *   <li><b>凭证形态按场景区分</b>：NATIVE→QR_CODE、H5→H5_URL、JSAPI/MINI_PROGRAM→JSAPI_PARAMS（FR-004）。</li>
 * </ol>
 */
class WechatChannelPluginTest {

    private static final String NOTIFY = "https://example.com/internal/channels/WECHAT/callback";

    /** 可编排的网关桩，记录被调用情况。 */
    private static final class StubGateway implements WechatGateway {

        PrepayResult prepayResult = PrepayResult.ok("weixin://wxpay/bizpayurl?pr=STUB");
        QueryResult queryResult = QueryResult.ok(TradeState.SUCCESS, "4200-STUB", "PM001", 1000L, "CNY");
        RefundResult refundResult = RefundResult.ok("RID-STUB", RefundState.SUCCESS);
        String jsapiParams = "{\"appId\":\"wx-stub\",\"paySign\":\"stub-sign\"}";
        PrepayCommand lastPrepay;
        RefundCommand lastRefund;
        String lastQueryOutTradeNo;
        String lastQueryTransactionId;
        int prepayCalls;

        @Override
        public PrepayResult prepay(PrepayCommand command) {
            prepayCalls++;
            lastPrepay = command;
            return prepayResult;
        }

        @Override
        public QueryResult queryByOutTradeNo(String outTradeNo) {
            lastQueryOutTradeNo = outTradeNo;
            return queryResult;
        }

        @Override
        public QueryResult queryByTransactionId(String transactionId) {
            lastQueryTransactionId = transactionId;
            return queryResult;
        }

        @Override
        public RefundResult refund(RefundCommand command) {
            lastRefund = command;
            return refundResult;
        }

        @Override
        public String jsapiPayParams(String prepayId) {
            return jsapiParams;
        }

        @Override
        public NotificationResult verifyAndDecrypt(NotificationEnvelope envelope) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    private static WechatPayProperties properties(boolean enabled) {
        WechatPayProperties p = new WechatPayProperties();
        p.setEnabled(enabled);
        p.setAppId("wx1234567890abcdef");
        p.setMchId("1900000109");
        p.setNotifyUrl(NOTIFY);
        return p;
    }

    private static WechatChannelPlugin plugin(WechatPayProperties properties, WechatGateway gateway) {
        return new WechatChannelPlugin(gateway, properties);
    }

    private static ChargeRequest charge(PaymentScene scene, Payer payer, String notifyUrl) {
        return new ChargeRequest("PM001", 10_00L, "CNY", "WECHAT",
                scene, Goods.of("测试商品"), new CallbackUrls(notifyUrl, null),
                Instant.now().plusSeconds(300), payer, null, null);
    }

    // ---- FR-003：描述符自描述 ----

    @Test
    @DisplayName("descriptor：code=WECHAT、支持真实模式、回调路径=WECHAT、场景收窄为四个 [FR-003]")
    void descriptorIsSelfDescribing() {
        WechatChannelPlugin plugin = plugin(properties(false), new StubGateway());

        assertThat(plugin.channelCode()).isEqualTo("WECHAT");
        assertThat(plugin.descriptor().displayName()).contains("微信");
        assertThat(plugin.descriptor().supportsRealMode()).isTrue();
        assertThat(plugin.descriptor().callbackPath()).isEqualTo("WECHAT");
        assertThat(plugin.acceptsCallback()).isTrue();
        assertThat(plugin.supportedScenes()).containsExactlyInAnyOrder(
                PaymentScene.NATIVE, PaymentScene.JSAPI, PaymentScene.MINI_PROGRAM, PaymentScene.H5);
        // 真实能力收窄：不声明 WEB / APP
        assertThat(plugin.supportedScenes()).doesNotContain(PaymentScene.WEB, PaymentScene.APP);
    }

    // ---- C-1 裁决 / FR-016：enabled 只门控真实模式，不门控注册 ----

    @Test
    @DisplayName("enabled=false ⇒ 真实模式未启用，但插件照常注册、MOCK 模态可用（C-1 裁决）[FR-016]")
    void disabledStillRegistersAndMockWorks() {
        StubGateway gateway = new StubGateway();
        WechatChannelPlugin plugin = plugin(properties(false), gateway);

        assertThat(plugin.isRealModeEnabled()).isFalse(); // 只门控真实模式
        assertThat(plugin.channelCode()).isEqualTo("WECHAT"); // 仍注册

        DyeContext.clear(); // 未染色 ⇒ MOCK
        ChannelResult result = plugin.charge(charge(PaymentScene.NATIVE, null, NOTIFY));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(result.hasCredential()).isFalse();  // mock 不产凭证
        assertThat(gateway.prepayCalls).isZero();      // 关键：一次都没调真实渠道
    }

    @Test
    @DisplayName("enabled=false + 染色 SANDBOX ⇒ 400，绝不静默回落 mock（C-1 裁决）[FR-241][INV-2]")
    void sandboxDyeWithoutEnabledFailsHard() {
        StubGateway gateway = new StubGateway();
        WechatChannelPlugin plugin = plugin(properties(false), gateway);

        DyeContext.set(DyeMode.SANDBOX);
        assertThatThrownBy(() -> plugin.charge(charge(PaymentScene.NATIVE, null, NOTIFY)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("refusing to silently fall back to mock");
        assertThat(gateway.prepayCalls).isZero();
    }

    @Test
    @DisplayName("enabled=false 时查询/退款同样不触达真实渠道（MOCK 走内核）[FR-010]")
    void disabledDoesNotTouchGatewayForQueryAndRefund() {
        StubGateway gateway = new StubGateway();
        WechatChannelPlugin plugin = plugin(properties(false), gateway);

        DyeContext.clear();
        plugin.queryStatus(new QueryStatusRequest("PM001", "TX1", "idem-1", "ch-1"));
        plugin.refund(new RefundRequest("PM001", "R001", 1000L, "CNY", "WECHAT",
                "ch-1", "R001", "用户申请", NOTIFY));

        assertThat(gateway.lastQueryOutTradeNo).isNull();
        assertThat(gateway.lastQueryTransactionId).isNull();
        assertThat(gateway.lastRefund).isNull();
    }

    // ---- C-3 裁决 / FR-015：仅 Spring @Component，无条件装配 ----

    @Test
    @DisplayName("工厂：无条件注册（无 @ConditionalOnProperty 等条件装配注解）[FR-015][FR-016]")
    void factoryIsUnconditionallyRegistered() {
        Annotation[] annotations = WechatChannelPluginFactory.class.getAnnotations();

        assertThat(annotations).anyMatch(a -> a.annotationType().getSimpleName().equals("Component"));
        assertThat(annotations)
                .as("enabled 只门控真实模式，MUST NOT 用条件装配门控注册（C-1 裁决）")
                .noneMatch(a -> a.annotationType().getSimpleName().startsWith("Conditional"));
    }

    @Test
    @DisplayName("工厂：descriptor 无需创建实例即可读；create() 产出插件 [FR-001]")
    void factoryCreatesPlugin() {
        WechatChannelPluginFactory factory = new WechatChannelPluginFactory(properties(false));

        assertThat(factory.descriptor().code()).isEqualTo("WECHAT");
        assertThat(factory.create()).isInstanceOf(WechatChannelPlugin.class);
    }

    // ---- FR-004：凭证形态按场景区分 ----

    @Test
    @DisplayName("NATIVE ⇒ accepted + QR_CODE 凭证（code_url）[FR-004]")
    void nativeProducesQrCodeCredential() {
        StubGateway gateway = new StubGateway();
        WechatChannelPlugin plugin = plugin(properties(true), gateway);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = plugin.charge(charge(PaymentScene.NATIVE, null, NOTIFY));

        assertThat(gateway.prepayCalls).isEqualTo(1);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN); // 受理 ≠ 成功
        assertThat(result.credential().kind()).isEqualTo(PayCredential.Kind.QR_CODE);
        assertThat(result.credential().payload()).isEqualTo("weixin://wxpay/bizpayurl?pr=STUB");
        assertThat(result.credential().isRedirectFamily()).isFalse(); // 二维码不是跳转家族
    }

    @Test
    @DisplayName("H5 ⇒ H5_URL 凭证；JSAPI ⇒ JSAPI_PARAMS 凭证 [FR-004]")
    void h5AndJsapiProduceTheirCredentials() {
        StubGateway gateway = new StubGateway();
        WechatChannelPlugin plugin = plugin(properties(true), gateway);
        DyeContext.set(DyeMode.SANDBOX);

        gateway.prepayResult = WechatGateway.PrepayResult.ok("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?x=1");
        ChannelResult h5 = plugin.charge(charge(PaymentScene.H5, null, NOTIFY));
        assertThat(h5.credential().kind()).isEqualTo(PayCredential.Kind.H5_URL);
        assertThat(h5.credential().isRedirectFamily()).isTrue();

        gateway.prepayResult = WechatGateway.PrepayResult.ok("wx201410272009395522657a690389285100");
        ChannelResult jsapi = plugin.charge(charge(PaymentScene.JSAPI, Payer.of("openid-1"), NOTIFY));
        assertThat(jsapi.credential().kind()).isEqualTo(PayCredential.Kind.JSAPI_PARAMS);
        assertThat(jsapi.credential().payload()).isEqualTo(gateway.jsapiParams);
        // openid 被透传给网关（JSAPI 必填）
        assertThat(gateway.lastPrepay.openid()).isEqualTo("openid-1");
    }

    @Test
    @DisplayName("MINI_PROGRAM 复用 JSAPI 端点，凭证同为 JSAPI_PARAMS [FR-003][FR-004]")
    void miniProgramReusesJsapiCredential() {
        StubGateway gateway = new StubGateway();
        WechatChannelPlugin plugin = plugin(properties(true), gateway);
        DyeContext.set(DyeMode.SANDBOX);

        gateway.prepayResult = WechatGateway.PrepayResult.ok("prepay-id-1");
        ChannelResult result = plugin.charge(charge(PaymentScene.MINI_PROGRAM, Payer.of("openid-2"), NOTIFY));

        assertThat(result.credential().kind()).isEqualTo(PayCredential.Kind.JSAPI_PARAMS);
        assertThat(gateway.lastPrepay.scene()).isEqualTo(PaymentScene.MINI_PROGRAM);
    }

    // ---- 前置校验：notifyUrl / openid / scene ----

    @Test
    @DisplayName("缺 notifyUrl ⇒ 400（returnUrl 不承载资金事实）[FR-004]")
    void missingNotifyUrlIsRejected() {
        // 配置里也不给（清空），请求也不带
        WechatPayProperties props = properties(true);
        props.setNotifyUrl(null);
        WechatChannelPlugin plugin = plugin(props, new StubGateway());

        DyeContext.set(DyeMode.SANDBOX);
        assertThatThrownBy(() -> plugin.charge(charge(PaymentScene.NATIVE, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("notifyUrl");
    }

    @Test
    @DisplayName("JSAPI 缺 openid ⇒ 400 [FR-004]")
    void jsapiWithoutOpenidIsRejected() {
        WechatChannelPlugin plugin = plugin(properties(true), new StubGateway());

        DyeContext.set(DyeMode.SANDBOX);
        assertThatThrownBy(() -> plugin.charge(charge(PaymentScene.JSAPI, null, NOTIFY)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("openid");
    }

    @Test
    @DisplayName("真实模式缺 scene ⇒ 明确拒绝，不猜默认端点 [FR-004]")
    void realChargeWithoutSceneIsRejected() {
        WechatChannelPlugin plugin = plugin(properties(true), new StubGateway());

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = plugin.charge(charge(null, null, NOTIFY));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(result.businessCode()).isEqualTo(BusinessCode.INVALID_REQUEST);
    }

    // ---- 错误码映射（渠道差异关在插件内） ----

    @Test
    @DisplayName("微信错误码 → 平台 BusinessCode；未知码一律 DECLINED，不泄漏原文 [FR-006]")
    void wechatErrorCodesAreTranslated() {
        assertThat(WechatChannelPlugin.mapErrorCode("NOTENOUGH")).isEqualTo(BusinessCode.INSUFFICIENT_FUNDS);
        assertThat(WechatChannelPlugin.mapErrorCode("PARAM_ERROR")).isEqualTo(BusinessCode.INVALID_REQUEST);
        assertThat(WechatChannelPlugin.mapErrorCode("SIGN_ERROR")).isEqualTo(BusinessCode.INVALID_REQUEST);
        assertThat(WechatChannelPlugin.mapErrorCode("SYSTEMERROR")).isEqualTo(BusinessCode.UNKNOWN);
        assertThat(WechatChannelPlugin.mapErrorCode("SOMETHING_NEW")).isEqualTo(BusinessCode.DECLINED);
        assertThat(WechatChannelPlugin.mapErrorCode(null)).isEqualTo(BusinessCode.DECLINED);
    }

    @Test
    @DisplayName("真实渠道业务拒绝 ⇒ businessFailure（不重试语义）[FR-006]")
    void businessRejectionIsNotRetryable() {
        StubGateway gateway = new StubGateway();
        gateway.prepayResult = WechatGateway.PrepayResult.businessFailure("NOTENOUGH", "余额不足");
        WechatChannelPlugin plugin = plugin(properties(true), gateway);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = plugin.charge(charge(PaymentScene.NATIVE, null, NOTIFY));

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(result.retryable()).isFalse();
        assertThat(result.businessCode()).isEqualTo(BusinessCode.INSUFFICIENT_FUNDS);
    }

    @Test
    @DisplayName("通信失败 ⇒ transportFailure（可重试），绝不臆断成败 [INV-3]")
    void transportFailureIsRetryable() {
        StubGateway gateway = new StubGateway();
        gateway.prepayResult = WechatGateway.PrepayResult.transportFailure("timeout");
        WechatChannelPlugin plugin = plugin(properties(true), gateway);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = plugin.charge(charge(PaymentScene.NATIVE, null, NOTIFY));

        assertThat(result.retryable()).isTrue();
        assertThat(result.hasCredential()).isFalse();
    }

    // ---- JSAPI paySign 基串（第二套签名，勿与请求签名混用） ----

    @Test
    @DisplayName("JSAPI paySign 基串 = appId\\ntimeStamp\\nnonceStr\\npackage\\n（与请求签名基串不同）[FR-004]")
    void jsapiPaySignBaseStringIsItsOwnFormat() {
        String base = WechatPaySigner.jsapiBaseString("wx123", "1554208460", "NONCE", "prepay_id=abc");

        assertThat(base).isEqualTo("wx123\n1554208460\nNONCE\nprepay_id=abc\n");
    }
}
