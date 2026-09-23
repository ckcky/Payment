package com.payment.payment.infra.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.payment.infra.channel.alipay.AlipayGateway;
import com.payment.payment.application.channel.CallbackUrls;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.Goods;
import com.payment.payment.application.channel.PayCredential;
import com.payment.payment.application.channel.PaymentScene;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 沙箱 {@code charge} 的<b>零分叉</b>测试（spec 030 / T120，SC-A-13 / T-A-15）。
 *
 * <p>本类回答一个很具体的风险问题：给 {@code AlipayChannelAdapter} 加上沙箱分支后，
 * <b>mock 分支的既有行为有没有被扰动？</b></p>
 *
 * <p>证据是确定性故障注入：mock 分支按 {@code amount_minor % 100} 尾数注入故障
 * （spec 022 / T429），尾数 {@code 11} ⇒ 渠道超时。若沙箱分支的引入（新增字段、
 * 新增 {@code DyeContext} 判断、新增网关装配）意外改了 mock 路径的口径，
 * 这条注入就会失效——所以这条断言是「零分叉」最灵敏的探针。</p>
 *
 * <p>与之对照的是沙箱分支：同一尾数 {@code 11} <b>不</b>触发 mock 注入（它根本不在 mock 路径上），
 * 而是走真实网关，产出凭证。两个模态各走各的，互不污染。</p>
 */
class AlipaySandboxChargeTest {

    /**
     * 真实 SDK 的 {@code pageExecute().getBody()} 返回形态：**自动提交表单 HTML**，不是 URL。
     *
     * <p>桩的载荷形态必须与真实网关一致，否则测的就不是真实契约——此前桩返回一个 URL 字符串，
     * 于是「凭证 Kind」这条断言跟着错标成了 {@code REDIRECT_URL}（2026-09-20 修正）。</p>
     */
    private static final String PAGE_PAY_FORM_HTML =
            "<form name=\"punchout_form\" method=\"post\" "
                    + "action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\">"
                    + "<input type=\"hidden\" name=\"biz_content\" value=\"{}\">"
                    + "<input type=\"submit\" value=\"立即支付\" style=\"display:none\"></form>"
                    + "<script>document.forms[0].submit();</script>";

    /** 只记录调用、可编排结果的最小网关桩。 */
    private static final class StubGateway implements AlipayGateway {
        PagePayResult pagePayResult = PagePayResult.ok(PAGE_PAY_FORM_HTML);
        int pagePayCalls;
        String lastOutTradeNo;
        long lastAmountMinor;
        String lastSubject;
        String lastNotifyUrl;

        @Override
        public PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                                     String subject, String notifyUrl, String returnUrl, Instant expireAt) {
            pagePayCalls++;
            lastOutTradeNo = outTradeNo;
            lastAmountMinor = amountMinor;
            lastSubject = subject;
            lastNotifyUrl = notifyUrl;
            return pagePayResult;
        }

        @Override
        public QueryResult query(String outTradeNo, String channelTransactionId) {
            return QueryResult.unknown("n/a");
        }

        @Override
        public RefundResult refund(String outTradeNo, String channelTransactionId, String refundNo,
                                   long amountMinor, String currency, String reason) {
            return RefundResult.ok("n/a");
        }

        @Override
        public boolean verifyNotify(Map<String, String> rawParams) {
            return true;
        }
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    private static ChargeRequest charge(long amountMinor) {
        return new ChargeRequest("PM030", 1L, amountMinor, "CNY", "ALIPAY",
                PaymentScene.WEB, Goods.of("沙箱测试商品"),
                new CallbackUrls("https://demo/notify", "https://demo/return"),
                Instant.parse("2026-09-20T12:00:00Z"), null, null, new HashMap<>());
    }

    // ---------- 零分叉探针：MOCK 尾数 11 仍 timeout ----------

    @Test
    @DisplayName("MOCK 分支尾数 11 仍 timeout（沙箱分支引入后 mock 口径零漂移）[SC-A-13][T-A-15]")
    void mockTailElevenStillTimesOut() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.clear(); // 未染色 ⇒ MOCK
        ChannelResult result = adapter.charge(charge(3_411L)); // % 100 == 11

        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(result.transportCode()).isEqualTo(com.payment.common.core.rpc.TransportCode.TIMEOUT);
        assertThat(result.retryable()).isTrue();
        assertThat(result.hasCredential()).isFalse();
        // 关键：mock 注入路径完全不碰沙箱网关
        assertThat(gateway.pagePayCalls).isZero();
    }

    @Test
    @DisplayName("显式染色 MOCK 同样走 mock 注入路径，不碰网关 [FR-130]")
    void explicitMockDyeAlsoStaysOnMockFaultPath() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.MOCK);
        ChannelResult result = adapter.charge(charge(3_411L));

        assertThat(result.transportCode()).isEqualTo(com.payment.common.core.rpc.TransportCode.TIMEOUT);
        assertThat(gateway.pagePayCalls).isZero();
    }

    @Test
    @DisplayName("MOCK 尾数 11 之外的普通金额仍按基线场景成功（未被沙箱改动影响）")
    void mockOrdinaryAmountStillSucceeds() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.clear();
        ChannelResult result = adapter.charge(charge(3_400L)); // % 100 == 0

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(result.hasCredential()).isFalse();
        assertThat(gateway.pagePayCalls).isZero();
    }

    // ---------- SANDBOX 分支：同一尾数走真实网关，产出凭证 ----------

    @Test
    @DisplayName("SANDBOX 分支尾数 11 不触发 mock 注入，改走真实网关并产出凭证 [SC-A-13]")
    void sandboxTailElevenGoesToRealGatewayInstead() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = adapter.charge(charge(3_411L));

        // 走的是真实网关，不是 mock 的 timeout 注入
        assertThat(gateway.pagePayCalls).isEqualTo(1);
        assertThat(result.hasCredential()).isTrue();
        assertThat(result.credential().kind()).isEqualTo(PayCredential.Kind.FORM_HTML);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN); // 受理 ≠ 成功
        assertThat(result.retryable()).isFalse();
    }

    @Test
    @DisplayName("SANDBOX ⇒ 产出 FORM_HTML 凭证（不是 REDIRECT_URL），且网关收到原始金额/单号/notifyUrl")
    void sandboxProducesRedirectCredentialAndPassesThrough() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = adapter.charge(charge(12_345L));

        assertThat(result.hasCredential()).isTrue();
        assertThat(result.credential().payload()).isEqualTo(PAGE_PAY_FORM_HTML);
        // 表单 HTML **不是**跳转家族：消费端不得直接 window.open（否则就是空白页）
        assertThat(result.credential().isRedirectFamily()).isFalse();
        // 凭证有效期跟随请求 expireAt（供前端判断「什么时候该重新下单」）
        assertThat(result.credential().expiresAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));
        // 网关收到的是**分**（换算发生在网关层，Adapter 不预换算——避免双处换算漂移）
        assertThat(gateway.lastAmountMinor).isEqualTo(12_345L);
        assertThat(gateway.lastOutTradeNo).isEqualTo("PM030");
        assertThat(gateway.lastNotifyUrl).isEqualTo("https://demo/notify");
        assertThat(gateway.lastSubject).isEqualTo("沙箱测试商品");
    }

    @Test
    @DisplayName("SANDBOX 网关通信失败 ⇒ transportFailure + 无凭证（不会给出一个打不开的链接）")
    void sandboxGatewayTransportFailureYieldsNoCredential() {
        StubGateway gateway = new StubGateway();
        gateway.pagePayResult = AlipayGateway.PagePayResult.transportFailure("connection reset");
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = adapter.charge(charge(12_345L));

        assertThat(result.hasCredential()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
    }

    @Test
    @DisplayName("沙箱未启用 + 染色 SANDBOX ⇒ 400 硬失败，且**未**触达网关（不静默回落）[FR-241][INV-8]")
    void sandboxWithoutEnabledFailsHardAndNeverTouchesGateway() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, false); // enabled=false

        DyeContext.set(DyeMode.SANDBOX);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.charge(charge(12_345L)))
                .isInstanceOf(com.payment.common.core.error.BizException.class)
                .hasMessageContaining("refusing to silently fall back to mock");

        // 硬失败路径不产生任何渠道交互
        assertThat(gateway.pagePayCalls).isZero();
    }
}
