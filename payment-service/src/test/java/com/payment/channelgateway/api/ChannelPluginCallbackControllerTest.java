package com.payment.channelgateway.api;

import com.payment.channelgateway.application.ChannelCallbackHandler;
import com.payment.channelgateway.application.ChannelRegistry;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.channelgateway.support.StubChannelRegistry;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.channel.ChannelPayNotified;
import com.payment.common.dto.channel.ChannelRefundNotified;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.payment.application.PayNotifyOutcome;
import com.payment.payment.application.PaymentNotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通用渠道回调端点的 <b>HTTP 适配层</b>（spec 037 / T5 / T6 / FR-009 / FR-015）。
 *
 * <h3>本类补的是什么</h3>
 * <p>FR-015 删除了支付宝专属端点 {@code AlipayNotifyController} 及其两个测试类，
 * 其中 {@code AlipayNotifyControllerTest} 是当时<b>唯一</b>在 HTTP 层面验证回调端点的测试。
 * 若不补这一类，「ack → HTTP」的映射就只剩场景测试间接覆盖——
 * 而这条映射恰恰承载着一条资金红线：<b>验签失败必须是 403，且不得触达任何状态推进</b>（INV-10）。
 * 端点自己把 403 写错了，域内的断言全绿也发现不了。</p>
 *
 * <h3>本类只钉两件事（其余归各自的测试）</h3>
 * <ol>
 *   <li><b>报文搬运</b>：HTTP 请求 → {@link ChannelCallbackEnvelope}。
 *       端点的全部职责就是「原样搬运」——表单参数、原始字节、小写头名。
 *       它<b>不做</b>任何渠道语义解析（那是插件的事，见 {@code AlipayCallbackParseTest}）；</li>
 *   <li><b>应答映射</b>：{@code signatureVerified()} ⇒ 200 / 403，body 原样透传。
 *       body 的构造权在渠道插件（渠道协议要求什么样就什么样），端点不许加工。</li>
 * </ol>
 *
 * <h3>一处刻意的行为差异（登记在案）</h3>
 * <p>被删除的 {@code AlipayNotifyController} 用
 * {@code consumes = application/x-www-form-urlencoded} 声明，非表单请求会被 Spring
 * 以 <b>415</b> 拒绝。通用端点<b>刻意不限制 content-type</b>：它要同时承载表单协议的渠道
 * （支付宝）与 JSON 协议的渠道（Stripe），限制成表单会让后者永远收不到回调。
 * 因此「JSON 报文不再被 415 拒绝、而是被原样交给插件」是<b>有意的变化</b>，
 * 由 {@link #jsonBodyIsHandedOverRawInsteadOfBeingRejected()} 显式钉住。</p>
 */
class ChannelPluginCallbackControllerTest {

    private static final String CODE = "STUB";
    private static final String PAYMENT_NO = "PM-CB-CTRL-1";

    private CapturingPlugin plugin;
    private AcceptingPort port;
    private ChannelPluginCallbackController controller;

    /** 捕获信封的插件桩：验签结果与应答体可编排（本类不测渠道语义）。 */
    private static final class CapturingPlugin implements ChannelPlugin {

        ChannelCallbackEnvelope received;
        boolean verifyOk = true;
        String ackBody = "success";

        @Override
        public ChannelPluginDescriptor descriptor() {
            return new ChannelPluginDescriptor(CODE, "Stub", Set.of(PaymentScene.WEB), false, "stub");
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ParsedCallback parseCallback(ChannelCallbackEnvelope envelope) {
            received = envelope;
            if (!verifyOk) {
                throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "signature verification failed");
            }
            return ParsedCallback.of(PAYMENT_NO, ChannelResult.success("ch-1"));
        }

        @Override
        public String callbackAckBody() {
            return ackBody;
        }
    }

    /** 恒受理的入向端口替身：本类不测业务校验。 */
    private static final class AcceptingPort implements PaymentNotifyPort {

        final List<ChannelPayNotified> pays = new ArrayList<>();

        @Override
        public PayNotifyOutcome onChannelPayResult(ChannelPayNotified notified) {
            pays.add(notified);
            return PayNotifyOutcome.accepted();
        }

        @Override
        public void onChannelRefundResult(ChannelRefundNotified notified) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        plugin = new CapturingPlugin();
        port = new AcceptingPort();
        ChannelRegistry registry = new StubChannelRegistry().register(CODE, plugin);
        controller = new ChannelPluginCallbackController(
                new ChannelCallbackHandler(registry, port, new NoopBusinessMetrics()));
    }

    private static MockHttpServletRequest request(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest formRequest(String body) {
        return request(MediaType.APPLICATION_FORM_URLENCODED_VALUE, body);
    }

    // ---- ① 报文搬运：HTTP → 信封 ----

    @Test
    @DisplayName("表单报文 ⇒ 解析成 formParams（支付宝验签要的是原始全量参数）[FR-202]")
    void formBodyIsParsedIntoFormParams() throws Exception {
        controller.onCallback(CODE, formRequest("out_trade_no=PM-1&trade_status=TRADE_SUCCESS&sign=abc"));

        assertThat(plugin.received.formParams())
                .containsEntry("out_trade_no", "PM-1")
                .containsEntry("trade_status", "TRADE_SUCCESS")
                .containsEntry("sign", "abc");
    }

    @Test
    @DisplayName("表单值按 UTF-8 解码（签名基串里的中文标题不能被解成乱码）")
    void formValuesAreUrlDecodedAsUtf8() throws Exception {
        controller.onCallback(CODE, formRequest("subject=%E5%95%86%E5%93%81&out_trade_no=PM-1"));

        assertThat(plugin.received.formParams()).containsEntry("subject", "商品");
    }

    @Test
    @DisplayName("头名统一小写（渠道签名头大小写不保证：Stripe-Signature / stripe-signature）")
    void headerNamesAreLowerCased() throws Exception {
        MockHttpServletRequest request = formRequest("out_trade_no=PM-1");
        request.addHeader("Stripe-Signature", "t=1,v1=abc");

        controller.onCallback(CODE, request);

        assertThat(plugin.received.headers()).containsEntry("stripe-signature", "t=1,v1=abc");
    }

    /**
     * 登记在案的<b>有意变化</b>：专属端点用 {@code consumes=form-urlencoded} 声明，
     * 非表单请求会被 Spring 以 415 拒绝；通用端点刻意不限制 content-type，
     * 否则 JSON 协议的渠道永远收不到回调。
     */
    @Test
    @DisplayName("JSON 报文 ⇒ 原样交给插件（刻意不限制 content-type，415 已被有意取消）")
    void jsonBodyIsHandedOverRawInsteadOfBeingRejected() throws Exception {
        String json = "{\"type\":\"payment_intent.succeeded\",\"id\":\"evt_1\"}";

        ResponseEntity<String> response = controller.onCallback(CODE, request(MediaType.APPLICATION_JSON_VALUE, json));

        assertThat(response.getStatusCode().value())
                .as("JSON 报文不得被 415 拒绝——多协议端点要同时承载表单与 JSON")
                .isNotEqualTo(415);
        assertThat(plugin.received.rawBody())
                .as("原始字节必须原样带给插件：重新序列化会改变 Stripe 的签名基串")
                .isEqualTo(json);
        assertThat(plugin.received.formParams())
                .as("非表单形态不得凭空解析出表单参数")
                .isEmpty();
    }

    // ---- ② 应答映射：ack → HTTP ----

    @Test
    @DisplayName("验签通过 ⇒ 200，且 body 原样透传插件声明的应答体（端点不许加工）")
    void verifiedAckBecomes200WithPluginBody() throws Exception {
        plugin.ackBody = "success";

        ResponseEntity<String> response = controller.onCallback(CODE, formRequest("out_trade_no=PM-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .as("支付宝靠字符串精确匹配判断「已收到」：多一个引号都会触发反复重推")
                .isEqualTo("success");
    }

    @Test
    @DisplayName("验签失败 ⇒ 403，且**不触达** Payment 侧（INV-10）")
    void signatureFailureBecomes403WithoutReachingPaymentSide() throws Exception {
        plugin.verifyOk = false;

        ResponseEntity<String> response = controller.onCallback(CODE, formRequest("out_trade_no=PM-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("rejected");
        assertThat(port.pays)
                .as("未验签的报文绝不能推进资金事实")
                .isEmpty();
    }

    @Test
    @DisplayName("业务侧拒绝 ⇒ 仍是 200（靠 body 表达语义，让渠道按策略重推）")
    void businessRejectionKeeps200() throws Exception {
        // 用一个恒拒绝的端口替换：业务拒绝不是「报文真伪未定」，故不改 HTTP 状态
        ChannelRegistry registry = new StubChannelRegistry().register(CODE, plugin);
        controller = new ChannelPluginCallbackController(new ChannelCallbackHandler(
                registry, new RejectingPort(), new NoopBusinessMetrics()));

        ResponseEntity<String> response = controller.onCallback(CODE, formRequest("out_trade_no=PM-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("rejected");
    }

    @Test
    @DisplayName("未注册渠道 ⇒ 直接失败，不伪装成 200/403（配置错误必须暴露）")
    void unknownChannelIsNotMaskedAsAnHttpStatus() {
        assertThatThrownBy(() -> controller.onCallback("NOPE", formRequest("out_trade_no=PM-1")))
                .isInstanceOf(BizException.class);
        assertThat(port.pays).isEmpty();
    }

    /** 业务侧拒绝的端口替身（INV-10 的对照面：报文可信，只是与我们记的对不上）。 */
    private static final class RejectingPort implements PaymentNotifyPort {

        @Override
        public PayNotifyOutcome onChannelPayResult(ChannelPayNotified notified) {
            return PayNotifyOutcome.rejected("amount mismatch: notified=1 expected=1000");
        }

        @Override
        public void onChannelRefundResult(ChannelRefundNotified notified) {
            throw new UnsupportedOperationException();
        }
    }
}
