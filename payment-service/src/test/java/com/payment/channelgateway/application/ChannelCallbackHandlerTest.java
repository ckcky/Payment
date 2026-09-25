package com.payment.channelgateway.application;

import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.channel.ChannelPayNotified;
import com.payment.common.dto.channel.ChannelPayStatus;
import com.payment.common.dto.channel.ChannelRefundNotified;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.payment.application.PayNotifyOutcome;
import com.payment.payment.application.PaymentNotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 037 / T5 / FR-009 / INV-2 / INV-5：渠道回调<b>第一层</b>（渠道网关域内）的模板方法。
 *
 * <p>本类钉住四步顺序与「验签失败不触达 Payment 侧」这条资金红线（INV-10）：
 * <ol>
 *   <li>① 工厂 + 策略选插件（按 {@code channelCode} 精确寻址，INV-6）；</li>
 *   <li>② 验签（插件钩子；失败 ⇒ 403 且<b>不触达</b> Payment 侧）；</li>
 *   <li>③ 报文转换（插件钩子；翻译成平台语义）；</li>
 *   <li>④ 内核统一封装跨域事件并经 {@link PaymentNotifyPort} 跨域通知。</li>
 * </ol>
 *
 * <p><b>为什么顺序要钉死</b>：把 ② 排到 ④ 之后（或省掉）意味着「未验签的报文也能推进资金事实」——
 * 任何人都能伪造一条通知让平台把单改成成功。顺序即防线。</p>
 */
class ChannelCallbackHandlerTest {

    private static final String CODE = "STUB";
    private static final String PAYMENT_NO = "PM-CB-1";

    private StubRegistry registry;
    private RecordingPort port;
    private ChannelCallbackHandler handler;
    private StubPlugin plugin;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        port = new RecordingPort();
        handler = new ChannelCallbackHandler(registry, port,
                new com.payment.common.core.observability.NoopBusinessMetrics());
        plugin = new StubPlugin();
        registry.channels.put(CODE, plugin);
    }

    private static ChannelCallbackEnvelope envelope() {
        return ChannelCallbackEnvelope.json(Map.of("stub-signature", "abc"), "{\"raw\":true}");
    }

    // ---- ① 选插件 ----

    @Test
    @DisplayName("① 按 channelCode 精确选插件；未注册渠道直接失败，不静默回落")
    void unknownChannelIsRejected() {
        assertThatThrownBy(() -> handler.handle("NOPE", envelope()))
                .isInstanceOf(BizException.class);
        assertThat(port.pays).isEmpty();
    }

    @Test
    @DisplayName("① 非插件渠道（只有能力契约）不得接收回调")
    void nonPluginChannelIsRejected() {
        registry.channels.put("PLAIN", new PlainChannel());

        assertThatThrownBy(() -> handler.handle("PLAIN", envelope()))
                .isInstanceOf(BizException.class);
        assertThat(port.pays).isEmpty();
    }

    @Test
    @DisplayName("① 未声明回调挂载点的插件不得接收回调")
    void pluginWithoutCallbackPathIsRejected() {
        StubPlugin noCallback = new StubPlugin();
        noCallback.callbackPath = null;
        registry.channels.put(CODE, noCallback);

        assertThatThrownBy(() -> handler.handle(CODE, envelope()))
                .isInstanceOf(BizException.class);
        assertThat(port.pays).isEmpty();
    }

    // ---- ②③ 验签 + 转换（插件钩子）----

    @Test
    @DisplayName("② 验签失败 ⇒ 拒绝且**不触达** Payment 侧（INV-10）")
    void signatureFailureStopsBeforePaymentSide() {
        plugin.verifyOk = false;

        ChannelCallbackAck ack = handler.handle(CODE, envelope());

        assertThat(ack.signatureVerified()).as("验签未通过").isFalse();
        assertThat(ack.body()).contains("rejected");
        assertThat(plugin.calls).containsExactly("parse");
        assertThat(port.pays).as("未验签的报文绝不能推进资金事实").isEmpty();
    }

    @Test
    @DisplayName("③ 原始信封原样交给插件（内核不做渠道语义解析）")
    void envelopeIsHandedToPluginUnchanged() {
        ChannelCallbackEnvelope envelope = envelope();

        handler.handle(CODE, envelope);

        assertThat(plugin.received).isSameAs(envelope);
    }

    // ---- 四步顺序 ----

    @Test
    @DisplayName("四步顺序：选插件 → 验签/转换 → 跨域通知（顺序不可换）")
    void stepsRunInFixedOrder() {
        handler.handle(CODE, envelope());

        assertThat(plugin.calls).as("插件只在②③被调用一次").containsExactly("parse");
        assertThat(port.pays).as("④ 在②③之后").hasSize(1);
        assertThat(plugin.order)
                .as("转换必须发生在跨域通知之前")
                .containsExactly("parse", "notify");
    }

    // ---- ④ 跨域通知 ----

    @Test
    @DisplayName("④ 跨域事件携带 channelCode 与归一化结论（不含渠道私有类型）")
    void notifiedEventCarriesNormalizedConclusion() {
        plugin.paymentNo = PAYMENT_NO;
        plugin.result = ChannelResult.success("ch-1");
        plugin.notifiedAmount = ParsedCallback.NotifiedAmount.of(10_00L, "CNY");

        handler.handle(CODE, envelope());

        ChannelPayNotified notified = port.pays.get(0);
        assertThat(notified.paymentNo()).isEqualTo(PAYMENT_NO);
        assertThat(notified.channelCode()).as("FR-006：MUST 携带 channelCode").isEqualTo(CODE);
        assertThat(notified.status()).isEqualTo(ChannelPayStatus.SUCCESS);
        assertThat(notified.channelTransactionId()).isEqualTo("ch-1");
        assertThat(notified.amountMinor()).isEqualTo(10_00L);
        assertThat(notified.currencyCode()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("④ 受理 ⇒ 返回插件声明的渠道应答体（支付宝认精确 success）")
    void acceptedReturnsPluginAckBody() {
        plugin.ackBody = "success";
        port.outcome = PayNotifyOutcome.accepted();

        ChannelCallbackAck ack = handler.handle(CODE, envelope());

        assertThat(ack.signatureVerified()).isTrue();
        assertThat(ack.body()).isEqualTo("success");
    }

    @Test
    @DisplayName("④ Payment 侧拒绝 ⇒ 非成功应答（让渠道按策略重推，不假装收到）")
    void rejectedReturnsRejectionBody() {
        port.outcome = PayNotifyOutcome.rejected("amount mismatch: notified=9999 expected=1000");

        ChannelCallbackAck ack = handler.handle(CODE, envelope());

        assertThat(ack.signatureVerified()).as("验签是通过的，只是业务侧拒绝").isTrue();
        assertThat(ack.body()).contains("rejected").contains("amount mismatch");
    }

    @Test
    @DisplayName("④ Payment 侧处理异常 ⇒ 应答「processing error」（不泄漏内部细节）")
    void errorReturnsProcessingErrorBody() {
        port.outcome = PayNotifyOutcome.error("boom");

        ChannelCallbackAck ack = handler.handle(CODE, envelope());

        assertThat(ack.signatureVerified()).isTrue();
        assertThat(ack.body()).isEqualTo("processing error");
    }

    // ---- 落点（SC-002 同向）----

    @Test
    @DisplayName("模板方法落在渠道网关域（回调入口归属网关域，FR-009）")
    void handlerResidesInGatewayDomain() {
        assertThat(ChannelCallbackHandler.class.getPackageName())
                .isEqualTo("com.payment.channelgateway.application");
    }

    // ===================== 测试替身 =====================

    /** 记录调用顺序的插件桩：验签/转换合并在一个钩子里（②③ 都是插件职责）。 */
    private static final class StubPlugin implements ChannelPlugin {

        final List<String> calls = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        ChannelCallbackEnvelope received;
        boolean verifyOk = true;
        String callbackPath = "stub";
        String paymentNo = "PM-CB-1";
        ChannelResult result = ChannelResult.success("ch-1");
        ParsedCallback.NotifiedAmount notifiedAmount = ParsedCallback.NotifiedAmount.UNKNOWN;
        String ackBody = "success";

        @Override
        public ChannelPluginDescriptor descriptor() {
            return new ChannelPluginDescriptor(CODE, "Stub", Set.of(PaymentScene.WEB), false, callbackPath);
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
            calls.add("parse");
            order.add("parse");
            received = envelope;
            if (!verifyOk) {
                throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "signature verification failed");
            }
            return new ParsedCallback(paymentNo, result, notifiedAmount);
        }

        @Override
        public String callbackAckBody() {
            return ackBody;
        }
    }

    /** 只有能力契约、没有插件契约的渠道。 */
    private static final class PlainChannel implements PaymentChannel {

        @Override
        public String channelCode() {
            return "PLAIN";
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
    }

    private static final class StubRegistry implements ChannelRegistry {

        final Map<String, PaymentChannel> channels = new LinkedHashMap<>();

        @Override
        public PaymentChannel resolve(String channelCode) {
            PaymentChannel channel = channels.get(channelCode == null ? null : channelCode.toUpperCase(Locale.ROOT));
            if (channel == null) {
                throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "not registered: " + channelCode);
            }
            return channel;
        }

        @Override
        public Set<String> registeredCodes() {
            return channels.keySet();
        }
    }

    /** 记录式入向端口替身：只关心「收到了什么」「返回什么结论」。 */
    private final class RecordingPort implements PaymentNotifyPort {

        final List<ChannelPayNotified> pays = new ArrayList<>();
        PayNotifyOutcome outcome = PayNotifyOutcome.accepted();

        @Override
        public PayNotifyOutcome onChannelPayResult(ChannelPayNotified notified) {
            pays.add(notified);
            plugin.order.add("notify");
            return outcome;
        }

        @Override
        public void onChannelRefundResult(ChannelRefundNotified notified) {
            throw new UnsupportedOperationException();
        }
    }
}
