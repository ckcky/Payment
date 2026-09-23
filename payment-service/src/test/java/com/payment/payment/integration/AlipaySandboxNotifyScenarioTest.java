package com.payment.payment.integration;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.payment.api.AlipayNotifyController;
import com.payment.payment.infra.channel.alipay.AlipayGateway;
import com.payment.payment.application.channel.CallbackUrls;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PayCredential;
import com.payment.payment.application.channel.PaymentScene;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.infra.channel.AlipayChannelAdapter;
import com.payment.payment.infra.config.AlipaySandboxProperties;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 030 / T126 / SC-A-05 / SC-A-13：<b>沙箱全链路离线场景</b>。
 *
 * <p>把 Phase 3~8 的成果串成一条真实的动线——<b>下单 → 拿凭证 → 收到 notify → 收敛</b>——
 * 全程离线（mock 掉 {@link AlipayGateway}，用固定签名向量），不依赖网络、不依赖支付宝账号。
 * 这是本 Feature 最接近真实演示的一步：前面的单测各自验证「一段」，本类验证「这条链走得通」。</p>
 *
 * <h3>为什么必须离线可跑</h3>
 * 沙箱真调需要手机装沙箱钱包 App 扫码，无法进 CI。把「全链路」拆成
 * 「离线可验证的编排」+「真实沙箱手工演练」两层，前者进 CI 防回归，
 * 后者由 demo 动线覆盖（Phase 9）。若本类依赖真实网络，它就会长期被 skip——
 * 等于没有回归保护。</p>
 *
 * <h3>动线（与 demo.html 沙箱动线同构）</h3>
 * <pre>
 *   ① 建单：payment=PROCESSING + attempt（stamp SANDBOX）
 *   ② 染色 SANDBOX 下单：adapter.charge → accepted + FORM_HTML 凭证，payment 仍 PROCESSING（INV-6）
 *   ③ 支付宝推 notify（TRADE_SUCCESS，金额一致）→ 200 "success"
 *   ④ 收敛：payment=SUCCEEDED，attempt=SUCCEEDED，通知 order
 * </pre>
 */
class AlipaySandboxNotifyScenarioTest {

    private static final String PAYMENT_NO = "PM-SCENARIO-1";
    private static final String NOTIFY_URL = "http://localhost:8084/internal/channels/alipay/notify";

    private PaymentTestStack stack;
    private InMemoryPaymentRepository payments;
    private InMemoryPaymentAttemptRepository attempts;
    private ScriptedGateway gateway;
    private AlipaySandboxProperties properties;
    private AlipayChannelAdapter adapter;
    private AlipayNotifyController notifyController;

    /**
     * 脚本化支付宝网关：按脚本演绎「下单返回自动提交表单 HTML」与「验签通过」，
     * 不触碰任何网络。query/refund 本动线不用。
     */
    private static final class ScriptedGateway implements AlipayGateway {
        /**
         * 真实 SDK 的 pagePay 返回形态：**自动提交表单 HTML**（不是 URL）。
         * 桩与真实网关形态一致，「凭证 Kind」这条断言才有意义。
         */
        String pageFormHtml = "<form name=\"punchout_form\" method=\"post\" "
                + "action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do?sign=fake-signed\">"
                + "<script>document.forms[0].submit();</script></form>";
        boolean pagePayCalled = false;
        String capturedOutTradeNo;
        long capturedAmountMinor;
        String capturedNotifyUrl;

        @Override
        public PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                                     String subject, String notifyUrl, String returnUrl, Instant expireAt) {
            pagePayCalled = true;
            capturedOutTradeNo = outTradeNo;
            capturedAmountMinor = amountMinor;
            capturedNotifyUrl = notifyUrl;
            return PagePayResult.ok(pageFormHtml);
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
            // 固定签名向量：本测试用 sign=fake 视为通过（真实验签在 AlipaySdkGateway 内）
            return "fake".equals(rawParams.get("sign"));
        }
    }

    @BeforeEach
    void setUp() {
        stack = new PaymentTestStack();
        payments = stack.payments;
        attempts = stack.attempts;

        // ① 建单：payment=PROCESSING，attempt 已 stamp SANDBOX
        payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));
        attempts.save(PaymentAttempt.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, PaymentAttemptStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX")));

        gateway = new ScriptedGateway();
        properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId("sandbox-app-1");

        adapter = new AlipayChannelAdapter(AlipayChannelAdapter.Scenario.SUCCESS, gateway, true);
        notifyController = new AlipayNotifyController(gateway, properties, stack.callback,
                payments, attempts, new com.payment.common.core.observability.NoopBusinessMetrics(),
                new com.payment.common.core.observability.StructuredAuditLogger());
    }

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    /** 构造一笔真实的沙箱下单请求（含 notifyUrl —— 沙箱下单的硬前提）。 */
    private ChargeRequest chargeRequest() {
        return new ChargeRequest(PAYMENT_NO, 10L, 10_00L, "CNY", "ALIPAY",
                PaymentScene.WEB, null, CallbackUrls.notifyOnly(NOTIFY_URL),
                Instant.now().plusSeconds(1800), null, null, null);
    }

    /** 构造一条验签可通过的支付宝 notify 报文。 */
    private Map<String, String> notifyParams(String tradeStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", PAYMENT_NO);
        params.put("trade_status", tradeStatus);
        params.put("trade_no", "ch-scenario-1");
        params.put("total_amount", "10.00");
        params.put("app_id", "sandbox-app-1");
        params.put("sign", "fake");
        params.put("sign_type", "RSA2");
        return params;
    }

    // ---- 完整动线 ----

    @Test
    @DisplayName("全链路：沙箱下单拿凭证 → payment 停 PROCESSING → notify 收敛 SUCCEEDED [SC-A-05]")
    void fullSandboxMotionLine() {
        // ② 染色 SANDBOX 下单
        ChannelResult chargeResult = DyeContext.callWith(DyeMode.SANDBOX, () -> adapter.charge(chargeRequest()));

        // 渠道受理 ≠ 买家已付款：OUTCOME=ACCEPTED，且带付款凭证（自动提交表单 HTML）
        assertThat(gateway.pagePayCalled).as("必须真的走到了沙箱网关").isTrue();
        assertThat(gateway.capturedOutTradeNo).isEqualTo(PAYMENT_NO);
        assertThat(gateway.capturedAmountMinor).as("金额必须是分（不经过 double）").isEqualTo(10_00L);
        assertThat(gateway.capturedNotifyUrl).as("notifyUrl 必须透传给渠道").isEqualTo(NOTIFY_URL);

        assertThat(chargeResult.credential()).isNotNull();
        assertThat(chargeResult.credential().kind()).isEqualTo(PayCredential.Kind.FORM_HTML);
        assertThat(chargeResult.credential().payload()).isEqualTo(gateway.pageFormHtml);

        // INV-6 关键断言：钱还没到，payment MUST 保持 PROCESSING，不得记账/通知
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("拿到凭证 ≠ 付款完成，payment 必须停在 PROCESSING（INV-6）")
                .isEqualTo(PaymentStatus.PROCESSING);

        // ③ 支付宝推 notify（金额一致）
        var response = notifyController.onNotify(notifyParams("TRADE_SUCCESS"));

        // ④ 收敛
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).as("响应体必须恰好是 success").isEqualTo("success");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("权威回调到达后才落终态")
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(attempts.findByPaymentNo(PAYMENT_NO).get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);

        // order 收到成功通知（收敛链路的下游副作用）
        assertThat(stack.order.succeededRequests)
                .as("收敛必须通知 order 服务")
                .isNotEmpty();
        assertThat(stack.order.succeededRequests.get(0).paymentNo())
                .as("通知必须指向同一支付单")
                .isEqualTo(PAYMENT_NO);
    }

    @Test
    @DisplayName("动线①下单单负：沙箱 charge 不产生任何资金副作用（无凭证时不落终态）[INV-6]")
    void chargeAloneLeavesPaymentUntouched() {
        DyeContext.callWith(DyeMode.SANDBOX, () -> adapter.charge(chargeRequest()));

        // 仅下单，未收到 notify ⇒ 状态不动、无 order 通知
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(stack.order.succeededRequests)
                .as("买家还没付钱，绝不能通知 order 支付成功")
                .isEmpty();
    }

    @Test
    @DisplayName("动线②验签失败：链路在第一步就断，不触达收敛（状态零改动）[SC-A-05][INV-10]")
    void badSignatureBreaksTheChain() {
        Map<String, String> params = notifyParams("TRADE_SUCCESS");
        params.put("sign", "wrong-signature");

        var response = notifyController.onNotify(params);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("验签失败必须一个字节状态都不动")
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(stack.order.succeededRequests).isEmpty();
    }

    @Test
    @DisplayName("动线②金额被篡改：拒绝推进，链路止于校验（不收敛）[SC-B2-01]")
    void tamperedAmountStopsTheChain() {
        Map<String, String> params = notifyParams("TRADE_SUCCESS");
        params.put("total_amount", "0.01"); // 篡改成一分钱

        var response = notifyController.onNotify(params);

        assertThat(response.getBody()).contains("rejected");
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(stack.order.succeededRequests).isEmpty();
    }

    @Test
    @DisplayName("动线③买家未付款：WAIT_BUYER_PAY 不推进，payment 停 UNKNOWN 待收敛 [FR-204][CB-10]")
    void waitBuyerPayDoesNotComplete() {
        notifyController.onNotify(notifyParams("WAIT_BUYER_PAY"));

        PaymentStatus status = payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus();
        assertThat(status).isNotEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(status).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stack.order.succeededRequests).isEmpty();
    }

    @Test
    @DisplayName("动线④交易关闭：TRADE_CLOSED 收敛 FAILED，不误报成功")
    void tradeClosedEndsAsFailed() {
        notifyController.onNotify(notifyParams("TRADE_CLOSED"));

        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(stack.order.succeededRequests).isEmpty();
    }

    @Test
    @DisplayName("沙箱未启用：染色 SANDBOX 下单硬失败 400，绝不静默回落 mock [FR-241][INV-8]")
    void sandboxDisabledFailsHard() {
        AlipayChannelAdapter disabled = new AlipayChannelAdapter(AlipayChannelAdapter.Scenario.SUCCESS, null, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> DyeContext.callWith(DyeMode.SANDBOX, () -> disabled.charge(chargeRequest())))
                .isInstanceOf(com.payment.common.core.error.BizException.class)
                .hasMessageContaining("refusing to silently fall back to mock");

        assertThat(gateway.pagePayCalled).as("未启用时不得触达网关").isFalse();
    }

    @Test
    @DisplayName("MOCK 染色：同一 adapter 走 mock 分支，不触达沙箱网关（零分叉）[SC-A-13]")
    void mockDyeKeepsMockPath() {
        ChannelResult result = DyeContext.callWith(DyeMode.MOCK, () -> adapter.charge(chargeRequest()));

        assertThat(gateway.pagePayCalled).as("mock 分支 MUST NOT 触达沙箱网关").isFalse();
        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS); // mock SUCCESS 场景同步成功
        assertThat(payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("mock 同步成功路径会推进（与沙箱的 accepted 语义不同，这是刻意的）")
                .isEqualTo(PaymentStatus.PROCESSING); // 直接调 adapter 未走应用编排，状态由编排推进
    }
}
