package com.payment.payment.infra.channel.alipay;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.payment.application.channel.AlipayGateway;
import com.payment.payment.application.channel.CallbackUrls;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.Goods;
import com.payment.payment.application.channel.PayCredential;
import com.payment.payment.application.channel.PaymentScene;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.infra.channel.AlipayChannelAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 030 / Phase 7：支付宝双模态分发（FR-130 / FR-131 / FR-136~FR-138 / FR-241）。
 *
 * <p>核心是两组断言：
 * <ol>
 *   <li><b>MOCK 分支语义不变</b>（FR-130）：不传染色时行为与 spec 028 时期逐字一致；</li>
 *   <li><b>SANDBOX 分支</b>：走网关、产凭证、映射三态，且未启用时<b>硬失败不回落</b>（FR-241）。</li>
 * </ol>
 */
class AlipayDualModeTest {

    /** 真实 SDK 的 pagePay 返回形态：**自动提交表单 HTML**（不是 URL），桩必须与之一致。 */
    private static final String PAGE_PAY_FORM_HTML =
            "<form name=\"punchout_form\" method=\"post\" "
                    + "action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\">"
                    + "<script>document.forms[0].submit();</script></form>";

    /** 可编排的网关桩，记录被调用情况。 */
    private static final class StubGateway implements AlipayGateway {

        PagePayResult pagePayResult = PagePayResult.ok(PAGE_PAY_FORM_HTML);
        QueryResult queryResult = QueryResult.unknown("waiting");
        RefundResult refundResult = RefundResult.ok("ch-refund-1");
        Map<String, String> lastQuery = new HashMap<>();
        Boolean verifyResult = true;
        int pagePayCalls;

        @Override
        public PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                                     String subject, String notifyUrl, String returnUrl, Instant expireAt) {
            pagePayCalls++;
            return pagePayResult;
        }

        @Override
        public QueryResult query(String outTradeNo, String channelTransactionId) {
            lastQuery.put("outTradeNo", outTradeNo);
            lastQuery.put("channelTransactionId", channelTransactionId);
            return queryResult;
        }

        @Override
        public RefundResult refund(String outTradeNo, String channelTransactionId, String refundNo,
                                   long amountMinor, String currency, String reason) {
            return refundResult;
        }

        @Override
        public boolean verifyNotify(Map<String, String> rawParams) {
            return verifyResult;
        }
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    private static ChargeRequest chargeRequest() {
        return new ChargeRequest("PM001", 1L, 10_00L, "CNY", "ALIPAY",
                PaymentScene.WEB, Goods.of("测试商品"), new CallbackUrls("https://x/notify", "https://x/return"),
                Instant.now().plusSeconds(300), null, null, null);
    }

    // ---- FR-130：MOCK 分支 super 委托，语义不变 ----

    @Test
    @DisplayName("MOCK（未染色）⇒ super 委托，不触达沙箱网关 [FR-130]")
    void mockModeDoesNotTouchSandbox() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.clear(); // 未染色 ⇒ MOCK
        ChannelResult result = adapter.charge(chargeRequest());

        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(result.hasCredential()).isFalse(); // mock 不产凭证
        assertThat(gateway.pagePayCalls).isZero();    // 关键：一次都没调沙箱
    }

    @Test
    @DisplayName("MOCK 显式染色 ⇒ 同样走 mock，不触达沙箱 [FR-130]")
    void explicitMockDyeStaysMock() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.MOCK);
        adapter.charge(chargeRequest());

        assertThat(gateway.pagePayCalls).isZero();
    }

    // ---- FR-136：沙箱 charge 产凭证 ----

    @Test
    @DisplayName("SANDBOX ⇒ 返回 accepted + FORM_HTML 凭证，payment 将停 PROCESSING [FR-136]")
    void sandboxChargeProducesCredential() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = adapter.charge(chargeRequest());

        assertThat(gateway.pagePayCalls).isEqualTo(1);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN); // 受理 ≠ 成功
        assertThat(result.hasCredential()).isTrue();
        assertThat(result.credential().kind()).isEqualTo(PayCredential.Kind.FORM_HTML);
        assertThat(result.credential().payload()).isEqualTo(PAGE_PAY_FORM_HTML);
        // 表单 HTML 不是跳转家族：消费端不得直接 window.open（否则打开的是空白页）
        assertThat(result.credential().isRedirectFamily()).isFalse();
    }

    @Test
    @DisplayName("沙箱 charge 通信失败 ⇒ transportFailure（可重试语义）[FR-136]")
    void sandboxChargeTransportFailure() {
        StubGateway gateway = new StubGateway();
        gateway.pagePayResult = AlipayGateway.PagePayResult.transportFailure("timeout");
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);

        DyeContext.set(DyeMode.SANDBOX);
        ChannelResult result = adapter.charge(chargeRequest());

        assertThat(result.retryable()).isTrue();
        assertThat(result.hasCredential()).isFalse();
    }

    @Test
    @DisplayName("沙箱 charge 缺 notifyUrl ⇒ 400（returnUrl 不承载资金事实）[FR-103]")
    void sandboxChargeRequiresNotifyUrl() {
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, new StubGateway(), true);
        ChargeRequest noNotify = new ChargeRequest("PM001", 1L, 10_00L, "CNY", "ALIPAY",
                PaymentScene.WEB, Goods.of("x"), CallbackUrls.notifyOnly(null), null, null, null, null);

        DyeContext.set(DyeMode.SANDBOX);
        assertThatThrownBy(() -> adapter.charge(noNotify))
                .isInstanceOf(com.payment.common.core.error.BizException.class)
                .hasMessageContaining("notifyUrl");
    }

    // ---- FR-241：染色 SANDBOX 但沙箱未启用 ⇒ 硬失败 ----

    @Test
    @DisplayName("SANDBOX 染色但沙箱未启用 ⇒ 400，绝不静默回落 mock [FR-241][INV-8]")
    void sandboxDyeWithoutEnabledFailsHard() {
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, null, false);

        DyeContext.set(DyeMode.SANDBOX);
        assertThatThrownBy(() -> adapter.charge(chargeRequest()))
                .isInstanceOf(com.payment.common.core.error.BizException.class)
                .hasMessageContaining("refusing to silently fall back to mock");
    }

    // ---- FR-137：查询三态映射 ----

    @Test
    @DisplayName("沙箱查询 TRADE_SUCCESS ⇒ success；CLOSED ⇒ businessFailure；WAIT_BUYER_PAY ⇒ unknown [FR-137]")
    void sandboxQueryMapsThreeStates() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);
        DyeContext.set(DyeMode.SANDBOX);
        QueryStatusRequest req = new QueryStatusRequest("PM001", "txn-1", "idem-1", "ch-txn-1");

        gateway.queryResult = new AlipayGateway.QueryResult(
                AlipayGateway.AlipayTradeStatus.SUCCESS, "ch-txn-1", 1000L, "CNY", true, "ok");
        assertThat(adapter.queryStatus(req).status()).isEqualTo(ChannelResult.Status.SUCCESS);

        gateway.queryResult = new AlipayGateway.QueryResult(
                AlipayGateway.AlipayTradeStatus.CLOSED, null, null, null, true, "closed");
        assertThat(adapter.queryStatus(req).status()).isEqualTo(ChannelResult.Status.FAILURE);

        gateway.queryResult = new AlipayGateway.QueryResult(
                AlipayGateway.AlipayTradeStatus.WAIT_BUYER_PAY, null, null, null, true, "waiting");
        ChannelResult waiting = adapter.queryStatus(req);
        assertThat(waiting.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(waiting.retryable()).isFalse(); // 等待不是通信失败，不该重试

        // 渠道交易号被正确透传给网关
        assertThat(gateway.lastQuery.get("channelTransactionId")).isEqualTo("ch-txn-1");
    }

    // ---- FR-138：退款同步返回 ----

    @Test
    @DisplayName("沙箱退款：成功 ⇒ success(channelRefundNo)；拒绝 ⇒ businessFailure [FR-138]")
    void sandboxRefundMapsSynchronously() {
        StubGateway gateway = new StubGateway();
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);
        DyeContext.set(DyeMode.SANDBOX);
        RefundRequest req = new RefundRequest("PM001", "R001", 10_00L, "CNY", "ALIPAY",
                "ch-txn-1", "R001", "user request", "https://x/notify");

        assertThat(adapter.refund(req).status()).isEqualTo(ChannelResult.Status.SUCCESS);

        gateway.refundResult = AlipayGateway.RefundResult.rejected("insufficient balance");
        assertThat(adapter.refund(req).status()).isEqualTo(ChannelResult.Status.FAILURE);
    }

    // ---- FR-131：能力声明分模态 ----

    @Test
    @DisplayName("supportsRealMode=true；supportedScenes 沙箱收窄为 WEB，mock 全支持 [FR-131]")
    void capabilityDeclarationIsModeAware() {
        AlipayChannelAdapter adapter = new AlipayChannelAdapter(
                AlipayChannelAdapter.Scenario.SUCCESS, new StubGateway(), true);

        assertThat(adapter.supportsRealMode()).isTrue();

        DyeContext.clear();
        assertThat(adapter.supportedScenes()).containsExactlyInAnyOrder(PaymentScene.values());

        DyeContext.set(DyeMode.SANDBOX);
        assertThat(adapter.supportedScenes()).containsExactly(PaymentScene.WEB);
    }

    // ---- FR-139 / INV-1：金额换算禁浮点 ----

    @Test
    @DisplayName("金额换算：分 → 元用 BigDecimal，无精度损失、无科学计数法 [FR-139][INV-1]")
    void amountConversionAvoidsFloatingPoint() {
        assertThat(AlipaySdkGateway.toYuanPlainString(1L)).isEqualTo("0.01");
        assertThat(AlipaySdkGateway.toYuanPlainString(100L)).isEqualTo("1.00");
        assertThat(AlipaySdkGateway.toYuanPlainString(12_345L)).isEqualTo("123.45");
        // 经典浮点陷阱：0.1+0.2 式误差在 BigDecimal 路径下不存在
        assertThat(AlipaySdkGateway.toYuanPlainString(1_000_00L)).isEqualTo("1000.00");
        assertThat(AlipaySdkGateway.parseYuanToMinor("123.45")).isEqualTo(12_345L);
        assertThat(AlipaySdkGateway.parseYuanToMinor("0.01")).isEqualTo(1L);
        assertThat(AlipaySdkGateway.parseYuanToMinor(null)).isNull();
        assertThat(AlipaySdkGateway.parseYuanToMinor("not-a-number")).isNull();
    }

    // ---- FR-137 尾注：状态映射不臆断 ----

    @Test
    @DisplayName("未知 trade_status 映射为 UNKNOWN，绝不猜成成功或失败 [FR-137]")
    void unknownTradeStatusIsNotGuessed() {
        assertThat(AlipaySdkGateway.mapTradeStatus("TRADE_SUCCESS"))
                .isEqualTo(AlipayGateway.AlipayTradeStatus.SUCCESS);
        assertThat(AlipaySdkGateway.mapTradeStatus("TRADE_FINISHED"))
                .isEqualTo(AlipayGateway.AlipayTradeStatus.SUCCESS);
        assertThat(AlipaySdkGateway.mapTradeStatus("TRADE_CLOSED"))
                .isEqualTo(AlipayGateway.AlipayTradeStatus.CLOSED);
        assertThat(AlipaySdkGateway.mapTradeStatus("WAIT_BUYER_PAY"))
                .isEqualTo(AlipayGateway.AlipayTradeStatus.WAIT_BUYER_PAY);
        assertThat(AlipaySdkGateway.mapTradeStatus("SOMETHING_NEW"))
                .isEqualTo(AlipayGateway.AlipayTradeStatus.UNKNOWN);
        assertThat(AlipaySdkGateway.mapTradeStatus(null))
                .isEqualTo(AlipayGateway.AlipayTradeStatus.UNKNOWN);
    }
}
