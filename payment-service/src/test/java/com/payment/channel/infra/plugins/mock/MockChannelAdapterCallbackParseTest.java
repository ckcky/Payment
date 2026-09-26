package com.payment.channel.infra.plugins.mock;

import com.payment.channel.application.ChannelResult;
import com.payment.channel.application.spi.ChannelCallbackEnvelope;
import com.payment.channel.application.spi.ParsedCallback;
import com.payment.common.core.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 041 / T12：MOCK 插件的回调报文解析（平台原生 JSON 格式）。
 *
 * <h3>为什么现在才需要它</h3>
 * <p>T12 之前 mock 族的 HTTP 回调由 <b>payment 域</b>的两个内部端点接收
 * （{@code /internal/payments/{paymentNo}/channel-callback} 与
 * {@code /internal/payments/refunds/{refundNo}/channel-callback}），报文由 Spring 的
 * {@code @RequestBody} 反序列化成 DTO——渠道域没有解析职责，也就没有可测的解析器。</p>
 *
 * <p>T12 把两个端点删除、回调收敛到唯一入口 {@code POST /callbacks/channels/{channelCode}}，
 * mock 族因此必须像真实渠道一样<b>自带报文解析</b>（{@code MockChannelAdapter#parseCallback}）。
 * 本类把该解析器的契约钉死：</p>
 * <ul>
 *   <li><b>判别式是寻址键字段</b>：带 {@code refundNo} ⇒ 退款分支；否则要求 {@code paymentNo}
 *       ⇒ 支付分支。二者对应 {@code PaymentResultPort} 的两条入向操作（FR-006）；</li>
 *   <li><b>三档结论只认字面量</b>：{@code SUCCESS|FAILURE|UNKNOWN}，非法值直接拒绝
 *       ——「猜一个结论」在资金路径上是资损级的错；</li>
 *   <li><b>读不出来就说读不出来</b>：金额缺省 ⇒ {@link ParsedCallback.NotifiedAmount#UNKNOWN}，
 *       绝不当成 0 元（会把「没读到」伪装成「金额不符」）；</li>
 *   <li><b>任何解析失败都不触达状态推进</b>（INV-10）：一律抛 {@code INVALID_ARGUMENT}，
 *       由端点转 403。</li>
 * </ul>
 *
 * @see MockChannelAdapter#parseCallback
 */
class MockChannelAdapterCallbackParseTest {

    private static ParsedCallback parse(String body) {
        return new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS)
                .parseCallback(ChannelCallbackEnvelope.json(Map.of(), body));
    }

    // ---- 支付分支 ----

    @Test
    @DisplayName("支付分支：带 paymentNo ⇒ ParsedPayCallback，金额币种原样带上")
    void payBranchCarriesAmount() {
        ParsedCallback parsed = parse("{\"paymentNo\":\"PM-1\",\"status\":\"SUCCESS\","
                + "\"channelReference\":\"ch-1\",\"amountMinor\":100,\"currencyCode\":\"CNY\"}");

        assertThat(parsed).isInstanceOf(ParsedCallback.ParsedPayCallback.class);
        ParsedCallback.ParsedPayCallback pay = (ParsedCallback.ParsedPayCallback) parsed;
        assertThat(pay.paymentNo()).isEqualTo("PM-1");
        assertThat(pay.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(pay.result().channelReference()).isEqualTo("ch-1");
        assertThat(pay.notifiedAmount().amountMinor()).isEqualTo(100L);
        assertThat(pay.notifiedAmount().currencyCode()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("支付分支：金额缺省 ⇒ UNKNOWN（不把「没读到」当成 0 元）")
    void payBranchWithoutAmountIsUnknown() {
        ParsedCallback.ParsedPayCallback pay = (ParsedCallback.ParsedPayCallback)
                parse("{\"paymentNo\":\"PM-1\",\"status\":\"SUCCESS\"}");

        assertThat(pay.notifiedAmount().amountMinor()).isNull();
        assertThat(pay.notifiedAmount().currencyCode()).isNull();
    }

    // ---- 退款分支 ----

    @Test
    @DisplayName("退款分支：带 refundNo ⇒ ParsedRefundCallback（退款不携带金额）")
    void refundBranch() {
        ParsedCallback parsed = parse("{\"refundNo\":\"PMRF-1\",\"status\":\"SUCCESS\","
                + "\"channelReference\":\"ch-r-1\"}");

        assertThat(parsed).isInstanceOf(ParsedCallback.ParsedRefundCallback.class);
        ParsedCallback.ParsedRefundCallback refund = (ParsedCallback.ParsedRefundCallback) parsed;
        assertThat(refund.refundNo()).isEqualTo("PMRF-1");
        assertThat(refund.result().status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(refund.result().channelReference()).isEqualTo("ch-r-1");
    }

    @Test
    @DisplayName("判别式优先级：同时带 refundNo 与 paymentNo ⇒ 判为退款（寻址键不歧义）")
    void refundNoWinsWhenBothKeysPresent() {
        ParsedCallback parsed = parse("{\"paymentNo\":\"PM-1\",\"refundNo\":\"PMRF-1\",\"status\":\"SUCCESS\"}");

        assertThat(parsed)
                .as("寻址键必须唯一确定一条入向操作；两键并存时退款优先，不得两边都走")
                .isInstanceOf(ParsedCallback.ParsedRefundCallback.class);
    }

    // ---- 三档结论映射 ----

    @Test
    @DisplayName("FAILURE / UNKNOWN 同样映射（UNKNOWN 不猜结论，INV-005）")
    void statusMapping() {
        assertThat(parse("{\"paymentNo\":\"PM-1\",\"status\":\"FAILURE\",\"reason\":\"declined\"}")
                .result().status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(parse("{\"paymentNo\":\"PM-1\",\"status\":\"FAILURE\",\"reason\":\"declined\"}")
                .result().reason()).isEqualTo("declined");
        assertThat(parse("{\"paymentNo\":\"PM-1\",\"status\":\"UNKNOWN\"}")
                .result().status()).isEqualTo(ChannelResult.Status.UNKNOWN);
    }

    // ---- 拒绝路径（INV-10：一律不触达状态推进）----

    @Test
    @DisplayName("缺寻址键 ⇒ INVALID_ARGUMENT（不猜分支）")
    void missingAddressingKeyIsRejected() {
        assertThatThrownBy(() -> parse("{\"status\":\"SUCCESS\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("paymentNo");
    }

    @Test
    @DisplayName("状态值非法 ⇒ 拒绝（不猜结论）")
    void invalidStatusIsRejected() {
        assertThatThrownBy(() -> parse("{\"paymentNo\":\"PM-1\",\"status\":\"PAID\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    @DisplayName("缺 status ⇒ 拒绝")
    void missingStatusIsRejected() {
        assertThatThrownBy(() -> parse("{\"paymentNo\":\"PM-1\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("空体 / 非法 JSON ⇒ 拒绝（不抛 NPE、不静默放行）")
    void unparseableBodyIsRejected() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> parse("not-json"))
                .isInstanceOf(BizException.class);
    }

    // ---- 自描述（T12 起 MOCK 有回调挂载点）----

    @Test
    @DisplayName("描述符声明回调挂载点 = 渠道码（否则唯一入口会以 NOT_FOUND 拒绝 mock 回调）")
    void descriptorDeclaresCallbackPath() {
        assertThat(new MockChannelAdapter().descriptor().callbackPath())
                .isEqualTo(MockChannelAdapter.CODE);
        assertThat(new MockChannelAdapter().acceptsCallback()).isTrue();
    }
}
