package com.payment.channelgateway.infra.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.channelgateway.infra.AlipayChannelAdapter;
import com.payment.common.core.error.BizException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 支付宝回调报文的<b>插件侧翻译</b>（spec 037 / T6 / FR-014 / FR-015）。
 *
 * <h3>本类替代了什么</h3>
 * 迁移前，这套翻译逻辑住在 {@code AlipayNotifyController}（渠道域的专属 HTTP 端点）里：
 * 端点自己验签、自己读 {@code out_trade_no}、自己把 {@code trade_status} 映射成平台语义。
 * FR-015 要求删除该端点、回调统一走通用端点，于是翻译必须<b>下沉到插件</b>
 * （{@link com.payment.channelgateway.application.spi.ChannelPlugin#parseCallback}）——
 * 通用端点因此完全不需要认识支付宝的字段名。
 *
 * <h3>本类刻意不断言什么</h3>
 * 金额/币种/引用归属的<b>校验</b>不在这里测：它们已由 {@code DefaultPaymentNotifyPort}
 * 统一承担（FR-011 / T5），本插件的职责只是把渠道声称的金额<b>读出来</b>
 * （{@link ParsedCallback.NotifiedAmount}），判定权在内核。把「读」和「判」分开测，
 * 才能保证「漏判」不会因为「读对了」而被掩盖。
 */
class AlipayCallbackParseTest {

    private static final String PAYMENT_NO = "PM-1";

    /** 验签结果可编排的网关桩（其余方法本类不走）。 */
    private static final class StubGateway implements AlipayGateway {
        boolean verifyResult = true;

        @Override
        public PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                                     String subject, String notifyUrl, String returnUrl, Instant expireAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueryResult query(String outTradeNo, String channelTransactionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult refund(String outTradeNo, String channelTransactionId, String refundNo,
                                   long amountMinor, String currency, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean verifyNotify(Map<String, String> rawParams) {
            return verifyResult;
        }
    }

    private StubGateway gateway;
    private AlipayChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        gateway = new StubGateway();
        adapter = new AlipayChannelAdapter(AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);
    }

    private static ChannelCallbackEnvelope envelope(String tradeStatus, String tradeNo, String totalAmount) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        if (tradeStatus != null) {
            params.put("trade_status", tradeStatus);
        }
        if (tradeNo != null) {
            params.put("trade_no", tradeNo);
        }
        if (totalAmount != null) {
            params.put("total_amount", totalAmount);
        }
        params.put("sign", "fake");
        params.put("sign_type", "RSA2");
        return ChannelCallbackEnvelope.form(Map.of(), params);
    }

    // ---- ① 状态映射（与查询路径同口径，FR-204 / CB-10） ----

    @Test
    @DisplayName("TRADE_SUCCESS ⇒ success，并带渠道流水号 [FR-204]")
    void tradeSuccessMapsToSuccess() {
        ParsedCallback parsed = adapter.parseCallback(envelope("TRADE_SUCCESS", "ch-1", "10.00"));

        assertThat(parsed.paymentNo()).isEqualTo(PAYMENT_NO);
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(parsed.result().channelReference()).isEqualTo("ch-1");
    }

    @Test
    @DisplayName("TRADE_FINISHED 与 TRADE_SUCCESS 同口径（已完结也是成功）[FR-204]")
    void tradeFinishedMapsToSuccess() {
        ParsedCallback parsed = adapter.parseCallback(envelope("TRADE_FINISHED", "ch-1", "10.00"));
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
    }

    @Test
    @DisplayName("TRADE_CLOSED ⇒ 业务失败（渠道明确关闭，不重试）[FR-204]")
    void tradeClosedMapsToBusinessFailure() {
        ParsedCallback parsed = adapter.parseCallback(envelope("TRADE_CLOSED", "ch-1", "10.00"));
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(parsed.result().retryable()).isFalse();
    }

    @Test
    @DisplayName("WAIT_BUYER_PAY ⇒ 无结论不推进（买家还没付 ≠ 这笔不会付）[FR-204]")
    void waitBuyerPayMapsToBusinessUnknown() {
        ParsedCallback parsed = adapter.parseCallback(envelope("WAIT_BUYER_PAY", "ch-1", "10.00"));
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.UNKNOWN);
    }

    @Test
    @DisplayName("未知状态同样无结论：绝不把不认识的状态当成功 [FR-204]")
    void unknownStatusMapsToBusinessUnknown() {
        ParsedCallback parsed = adapter.parseCallback(envelope("SOMETHING_NEW", "ch-1", "10.00"));
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.UNKNOWN);
    }

    // ---- ② 金额翻译（插件只「读」，内核负责「判」） ----

    @Test
    @DisplayName("金额被翻译成「分」，币种按支付宝境内语义取 CNY [FR-210]")
    void amountIsTranslatedToMinorUnits() {
        ParsedCallback parsed = adapter.parseCallback(envelope("TRADE_SUCCESS", "ch-1", "10.00"));

        assertThat(parsed.notifiedAmount().isKnown()).isTrue();
        assertThat(parsed.notifiedAmount().amountMinor()).isEqualTo(1000L);
        assertThat(parsed.notifiedAmount().currencyCode()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("报文无金额 ⇒ UNKNOWN（内核据此跳过校验，绝不把「没读到」当成 0 元）[FR-210]")
    void missingAmountIsUnknownNotZero() {
        ParsedCallback parsed = adapter.parseCallback(envelope("TRADE_SUCCESS", "ch-1", null));

        assertThat(parsed.notifiedAmount()).isEqualTo(ParsedCallback.NotifiedAmount.UNKNOWN);
        assertThat(parsed.notifiedAmount().isKnown()).isFalse();
    }

    @Test
    @DisplayName("金额不是合法数值 ⇒ 同样 UNKNOWN，不臆断出一个假金额")
    void malformedAmountIsUnknown() {
        ParsedCallback parsed = adapter.parseCallback(envelope("TRADE_SUCCESS", "ch-1", "not-a-number"));
        assertThat(parsed.notifiedAmount().isKnown()).isFalse();
    }

    // ---- ③ 身份与定位（①签名/身份 段，FR-202 / FR-203） ----

    @Test
    @DisplayName("验签失败 ⇒ 抛 BizException，不产生任何解析结果（不触达状态推进）[FR-202][INV-10]")
    void signatureFailureThrows() {
        gateway.verifyResult = false;

        assertThatThrownBy(() -> adapter.parseCallback(envelope("TRADE_SUCCESS", "ch-1", "10.00")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("缺少 out_trade_no ⇒ 抛 BizException（定位不到单据时绝不猜，猜就是把钱记到别人头上）")
    void missingOutTradeNoThrows() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("trade_no", "ch-1");
        params.put("sign", "fake");

        assertThatThrownBy(() -> adapter.parseCallback(ChannelCallbackEnvelope.form(Map.of(), params)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("app_id 与本平台配置不符 ⇒ 抛 BizException（FR-203：这条通知不是发给我们的）")
    void appIdMismatchThrows() {
        AlipayChannelAdapter configured = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, new StubGateway(), true, "sandbox-app-1");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("trade_no", "ch-1");
        params.put("app_id", "someone-elses");
        params.put("sign", "fake");

        assertThatThrownBy(() -> configured.parseCallback(ChannelCallbackEnvelope.form(Map.of(), params)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("app_id");
    }

    @Test
    @DisplayName("app_id 与配置一致 ⇒ 正常翻译（身份校验不得误伤合法通知）")
    void matchingAppIdPasses() {
        AlipayChannelAdapter configured = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, new StubGateway(), true, "sandbox-app-1");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("trade_no", "ch-1");
        params.put("app_id", "sandbox-app-1");
        params.put("sign", "fake");

        ParsedCallback parsed = configured.parseCallback(ChannelCallbackEnvelope.form(Map.of(), params));
        assertThat(parsed.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
    }

    // ---- ④ 渠道协议面（SPI-01） ----

    @Test
    @DisplayName("应答体恰好是纯文本 success（支付宝靠字符串精确匹配判断已收到）[FR-206]")
    void ackBodyIsExactlySuccess() {
        assertThat(adapter.callbackAckBody()).isEqualTo("success");
    }

    @Test
    @DisplayName("本插件声明接收回调，通用端点据此路由 [SPI-01]")
    void acceptsCallback() {
        assertThat(adapter.acceptsCallback()).isTrue();
        assertThat(adapter.descriptor().callbackPath()).isNotBlank();
    }
}
