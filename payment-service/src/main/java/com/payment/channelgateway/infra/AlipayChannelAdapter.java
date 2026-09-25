package com.payment.channelgateway.infra;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.core.rpc.TransportCode;
import com.payment.channelgateway.infra.alipay.AlipayGateway;
import com.payment.common.dto.channel.CallbackUrls;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.common.dto.channel.PayCredential;
import com.payment.channelgateway.application.PaymentChannel;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.infra.config.AlipaySandboxProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 支付宝渠道 Adapter（Feature 028 / FR-010，ADR-0072；spec 030 扩为<b>双模态</b>）。
 *
 * <p>本类身份 {@code ALIPAY}，<b>单 Adapter 双模态</b>（FR-130 / N11）：
 * 按 {@link DyeContext} 分发——{@code MOCK} 走既有 mock 语义，{@code SANDBOX} 走真实渠道协议。
 * <b>刻意不新建 {@code AlipaySandboxChannelAdapter}</b>：两个类会让「渠道身份」这个概念分叉，
 * 而渠道与渠道注册表是按 code 寻址的——同一个 code 对应两个实现，选错就退到了别人家。</p>
 *
 * <h3>MOCK 分支 MUST {@code super} 委托（FR-130 硬约束）</h3>
 * 基类 {@link AbstractMockChannelAdapter} 承载 4 件横切行为：金额尾数注入（e2e 确定性故障
 * 注入）、退款异步推送、每实例独立 {@code runId}、严格枚举解析。
 * 沙箱分支的引入<b>不得</b>让这些行为在 mock 路径上出现任何口径漂移——
 * 所以 mock 分支一律 {@code super.xxx(...)}，<b>不复制、不重写、不优化</b>。</p>
 *
 * <h3>沙箱未启用时的硬失败（FR-241 / INV-8）</h3>
 * 染色为 {@code SANDBOX} 而 {@code sandbox.enabled=false} ⇒ <b>400 INVALID_ARGUMENT</b>，
 * <b>不静默回落 mock</b>。静默回落最坏的结果是「以为在测沙箱、其实钱根本没进真实渠道」——
 * 这种假象会让演练结论完全失真。</p>
 */
@Component
public class AlipayChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "ALIPAY";

    /**
     * 沙箱支持的场景（FR-131 按真实能力<b>收窄</b>）。
     *
     * <p>电脑网站支付当前只做 {@code WEB}——真实渠道的场景是开通项，不是想要就有。
     * 收窄而非「默认全支持」是因为：声明支持却拿不到凭证，调用方会以为能付款，
     * 买家打开是个错误页。</p>
     */
    private static final Set<PaymentScene> SANDBOX_SCENES = Set.of(PaymentScene.WEB);

    /**
     * mock 模态下的场景全集——与 {@link PaymentChannel#supportedScenes()} 的接口默认值
     * <b>同一定义</b>（{@code Set.of(values())}）。
     *
     * <p>显式列出而非靠 {@code super} 调用：接口 default 方法在类外无法用
     * {@code Interface.super} 语法直接引用（那是继承链上的语法糖，不是接口限定调用），
     * 且 mock 全支持这个事实本来就该在 Adapter 里写清楚，而不是藏在接口默认值里。</p>
     */
    private static final Set<PaymentScene> DEFAULT_ALL_SCENES = Set.of(PaymentScene.values());

    /**
     * 沙箱网关（可空）。用 {@link ObjectProvider} 而非直接注入：Bean 由
     * {@code enabled=true} 条件装配，{@code enabled=false} 时容器里没有它，
     * 直接注入会让整个 Adapter 装配失败——那等于「沙箱没开，连 mock 都不能用了」。
     */
    private final AlipayGateway sandboxGateway;

    /** 沙箱开关：用于在染色 SANDBOX 但未启用时给出明确错误（FR-241）。 */
    private final boolean sandboxEnabled;

    /**
     * Spring 主构造：场景优先读 {@code payment.channel.adapters.ALIPAY.scenario}，
     * 未配则回落全局 {@code payment.channel.mock-scenario}（FR-014）。
     */
    @Autowired
    public AlipayChannelAdapter(
            @Value("${payment.channel.adapters.ALIPAY.scenario:${payment.channel.mock-scenario:SUCCESS}}") String scenario,
            @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
            @Value("${payment.channel.refund-async:true}") boolean refundAsync,
            @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs,
            ObjectProvider<AlipayGateway> sandboxGateway,
            AlipaySandboxProperties sandboxProperties) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
        this.sandboxGateway = sandboxGateway.getIfAvailable();
        this.sandboxEnabled = sandboxProperties.isEnabled();
    }

    /** 便捷构造（测试/演示脚本显式指定形态）：无沙箱能力，等价于 spec 028 时期的行为。 */
    public AlipayChannelAdapter(Scenario scenario) {
        super(scenario, 1500L, false, 1000L);
        this.sandboxGateway = null;
        this.sandboxEnabled = false;
    }

    /** 供测试直接注入网关（不经过 Spring 条件装配）。 */
    public AlipayChannelAdapter(Scenario scenario, AlipayGateway sandboxGateway, boolean sandboxEnabled) {
        super(scenario, 1500L, false, 1000L);
        this.sandboxGateway = sandboxGateway;
        this.sandboxEnabled = sandboxEnabled;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    // ---- spec 030 / FR-131 / FR-109：能力声明 ----

    /** 沙箱支持真实渠道（FR-131）；其余三个渠道维持 {@code default false}。 */
    @Override
    public boolean supportsRealMode() {
        return true;
    }

    /**
     * 支持的场景（FR-131）：<b>沙箱按真实能力收窄</b>，mock 保持全支持。
     *
     * <p>为什么要分模态给：mock 下所有场景都是本地模拟（声明全支持不产生任何后果），
     * 而沙箱下声明了却不支持会让调用方拿到一个打不开的链接。</p>
     */
    @Override
    public Set<PaymentScene> supportedScenes() {
        // 沙箱按真实能力收窄；mock 走接口默认（全支持）
        return DyeContext.isSandbox() ? SANDBOX_SCENES : DEFAULT_ALL_SCENES;
    }

    // ---- 双模态分发 ----

    @Override
    public ChannelResult charge(ChargeRequest request) {
        if (!DyeContext.isSandbox()) {
            // MOCK 分支：super 委托，基类 4 件横切行为口径 100% 不变（FR-130）
            return super.charge(request);
        }
        requireSandboxEnabled();
        return sandboxCharge(request);
    }

    @Override
    public ChannelResult refund(RefundRequest request) {
        if (!DyeContext.isSandbox()) {
            return super.refund(request); // MOCK：super 委托（FR-130）
        }
        requireSandboxEnabled();
        return sandboxRefund(request);
    }

    @Override
    public ChannelResult queryStatus(QueryStatusRequest request) {
        if (!DyeContext.isSandbox()) {
            return super.queryStatus(request); // MOCK：super 委托（FR-130）
        }
        requireSandboxEnabled();
        return sandboxQuery(request);
    }

    // ---- 沙箱分支 ----

    /**
     * 沙箱下单（FR-136）：{@code alipay.trade.page.pay} 的**自动提交表单 HTML** 作为凭证。
     *
     * <p>关键语义：返回 {@code accepted(null, "awaiting buyer", credential)}——
     * <b>渠道受理 ≠ 买家已付款</b>。渠道流水号此时通常还没有（买家还没付），故 {@code null}。
     * 调用方据此让 payment 停 {@code PROCESSING}（INV-6：不记账、不通知 order）。</p>
     *
     * <p><b>Kind 修正（2026-09-20）</b>：官方 SDK 的 {@code pageExecute().getBody()} 返回的是
     * 整段 {@code <form>} HTML，<b>不是 URL</b>（官方示例的用法是「把表单 HTML 直接写到页面上」）。
     * 此前这里标成 {@link PayCredential.Kind#REDIRECT_URL}，使
     * {@code credential.isRedirectFamily()} 对被误标的凭证返回 {@code true}——
     * 消费端据此「直接 {@code window.open}」，浏览器把 HTML 当相对地址解析 ⇒ <b>空白页</b>。
     * 现改用 {@link PayCredential.Kind#FORM_HTML}（该枚举本就为此而设，此前无人使用）。</p>
     */
    private ChannelResult sandboxCharge(ChargeRequest request) {
        CallbackUrls callbackUrls = request.callbackUrls();
        if (callbackUrls == null || callbackUrls.notifyUrl() == null || callbackUrls.notifyUrl().isBlank()) {
            // 没有 notifyUrl 就拿不到资金事实：页面跳转回来（returnUrl）不承载资金事实，
            // MUST NOT 据其推进支付状态（FR-103）。这是配置错误，不是渠道错误。
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "sandbox charge requires callbackUrls.notifyUrl: the notify callback is the only "
                            + "trustworthy source of fund facts; returnUrl must not drive payment state");
        }

        Instant expireAt = request.expireAt();
        AlipayGateway.PagePayResult result = sandboxGateway.pagePay(
                request.paymentNo(),
                request.amountMinor(),
                request.currencyCode(),
                subjectOf(request),
                callbackUrls.notifyUrl(),
                callbackUrls.returnUrl(),
                expireAt);

        if (!result.transportOk()) {
            // 通信失败 ⇒ 可重试语义（与 mock 的 TIMEOUT 同一处理路径）
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.reason());
        }
        PayCredential credential = PayCredential.formHtml(result.redirectUrl(), expireAt);
        return ChannelResult.accepted(null, "awaiting buyer", credential);
    }

    /**
     * 沙箱退款（FR-138）：{@code alipay.trade.refund} <b>同步返回</b>，三个分支都映射为
     * 明确结论（沙箱退款不像 mock 那样异步推送）。
     */
    private ChannelResult sandboxRefund(RefundRequest request) {
        AlipayGateway.RefundResult result = sandboxGateway.refund(
                request.paymentNo(),
                request.channelTransactionId(),
                request.refundNo(),
                request.amountMinor(),
                request.currencyCode(),
                request.reason());

        if (!result.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.reason());
        }
        if (result.succeeded()) {
            return ChannelResult.success(result.channelRefundNo());
        }
        // 渠道明确拒绝：映射为业务失败（不重试）。渠道私有错误码不外泄（结果里已归一化）
        return ChannelResult.businessFailure(null, result.reason(), BusinessCode.DECLINED);
    }

    /**
     * 沙箱查询（FR-137）：三态映射，**不臆断**。
     *
     * <p>{@code WAIT_BUYER_PAY} → {@code businessUnknown}：买家还没付不等于这笔不会付，
     * 平台侧应保持待收敛，而不是落一个 FAILED 把订单关掉。</p>
     */
    private ChannelResult sandboxQuery(QueryStatusRequest request) {
        AlipayGateway.QueryResult result = sandboxGateway.query(
                request.paymentNo(), request.channelTransactionId());

        if (!result.transportOk()) {
            // 通信失败算可重试（超时/断连都可能只是网络抖动），重试耗尽后进 UNKNOWN 待收敛
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.reason());
        }
        return switch (result.tradeStatus()) {
            case SUCCESS -> ChannelResult.success(result.channelTransactionId());
            case CLOSED -> ChannelResult.businessFailure(result.channelTransactionId(),
                    "alipay trade closed (buyer did not pay in time)", BusinessCode.DECLINED);
            case WAIT_BUYER_PAY, UNKNOWN -> ChannelResult.businessUnknown(result.reason());
        };
    }

    /**
     * 沙箱未启用时的硬失败（FR-241 / INV-8）。
     *
     * <p>抛 400 而非回落 mock：调用方显式要求走真实渠道，平台却偷偷走了模拟——
     * 这是<b>语义欺骗</b>。演练、对账、准入判断都会因此失真。</p>
     */
    private void requireSandboxEnabled() {
        if (sandboxGateway == null || !sandboxEnabled) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "X-Dye-Tag=SANDBOX requires payment.channel.adapters.alipay.sandbox.enabled=true; "
                            + "refusing to silently fall back to mock mode");
        }
    }

    /** 商品标题：无 goods 时回落支付单号，保证渠道侧有可读标识（渠道要求 subject 非空）。 */
    private static String subjectOf(ChargeRequest request) {
        return request.goods() == null ? request.paymentNo() : request.goods().title();
    }
}
