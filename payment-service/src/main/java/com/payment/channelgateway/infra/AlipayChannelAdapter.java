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
import com.payment.common.dto.channel.PaymentScene;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.channelgateway.infra.config.AlipaySandboxProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 支付宝渠道插件（Feature 028 / FR-010，ADR-0072；spec 030 扩为<b>双模态</b>；
 * spec 037 / T6 迁为插件范式）。
 *
 * <p>本类身份 {@code ALIPAY}，<b>单 Adapter 双模态</b>（FR-130 / N11）：
 * 按染色分发——{@code MOCK} 走 mock 语义，{@code SANDBOX} 走真实渠道协议。
 * <b>刻意不新建 {@code AlipaySandboxChannelAdapter}</b>：两个类会让「渠道身份」这个概念分叉，
 * 而渠道与渠道注册表是按 code 寻址的——同一个 code 对应两个实现，选错就退到了别人家。</p>
 *
 * <h3>模态分派改由内核模板方法承担（spec 037 / T6）</h3>
 * 迁移前本类<b>自己覆写</b> {@code charge} / {@code refund} / {@code queryStatus} 做
 * 「{@code DyeContext.isSandbox()} ? 沙箱原语 : super}」的分派，而
 * {@code AbstractChannelPlugin} 把这三个方法声明为 {@code final}——两者直接冲突，
 * 这正是「存量渠道无法迁移」的技术根因。
 *
 * <p>迁移后分派<b>收归内核</b>（模板方法第 ③ 步），本类只提供三个真实模式原语
 * （{@code doRealCharge} / {@code doRealRefund} / {@code doRealQuery}）与
 * {@link #isRealModeEnabled()}。于是：
 * <ul>
 *   <li><b>mock 路径</b>零漂移——它本来就该走内核统一的 mock 语义，
 *       而不是「先经过本类的 if 再 super 转发」；</li>
 *   <li><b>沙箱未启用时的硬失败</b>（FR-241 / INV-8）由内核门控统一给出，
 *       本类不再自带一份 {@code requireSandboxEnabled}；</li>
 *   <li><b>能力校验</b>（FR-131）现在也覆盖沙箱路径——迁移前沙箱分支不校验场景，
 *       于是「声明只支持 WEB、却被要求 H5」会一路走到渠道侧才失败。</li>
 * </ul>
 */
@Component
public class AlipayChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "ALIPAY";

    /** 通用回调端点挂载路径段：{@code POST /internal/channels/ALIPAY/callback}（FR-015）。 */
    public static final String CALLBACK_PATH = "ALIPAY";

    // 支付宝交易状态原文（本类内做映射，不外泄，FR-141）
    private static final String STATUS_TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String STATUS_TRADE_FINISHED = "TRADE_FINISHED";
    private static final String STATUS_TRADE_CLOSED = "TRADE_CLOSED";

    /**
     * 沙箱支持的场景（FR-131 按真实能力<b>收窄</b>）。
     *
     * <p>电脑网站支付当前只做 {@code WEB}——真实渠道的场景是开通项，不是想要就有。
     * 收窄而非「默认全支持」是因为：声明支持却拿不到凭证，调用方会以为能付款，
     * 买家打开是个错误页。</p>
     */
    private static final Set<PaymentScene> SANDBOX_SCENES = Set.of(PaymentScene.WEB);

    /**
     * mock 模态下的场景全集——与迁移前 {@code PaymentChannel#supportedScenes()} 的接口默认值
     * <b>同一定义</b>（{@code Set.of(values())}）。
     *
     * <p>显式列出而非靠 {@code super} 调用：接口 default 方法在类外无法用
     * {@code Interface.super} 语法直接引用（那是继承链上的语法糖，不是接口限定调用），
     * 且 mock 全支持这个事实本来就该在 Adapter 里写清楚，而不是藏在接口默认值里。</p>
     */
    private static final Set<PaymentScene> DEFAULT_ALL_SCENES = Set.of(PaymentScene.values());

    /**
     * 插件自描述（SPI-01）。
     *
     * <p>{@code supportedScenes} 取<b>全集</b>而非 {@code SANDBOX_SCENES}：
     * 描述符是「本渠道声明支持哪些场景」的静态声明，而本类在 mock 模态下确实支持全集
     * （{@link #supportedScenes()} 覆写为按模态回答）。若描述符只写 {@code {WEB}}，
     * 任何读描述符的消费点（运维面 / 路由 / 注册表）都会误判 mock 能力。</p>
     */
    private static final ChannelPluginDescriptor DESCRIPTOR =
            new ChannelPluginDescriptor(CODE, "支付宝（电脑网站支付）",
                    DEFAULT_ALL_SCENES, true, CALLBACK_PATH);

    /**
     * 沙箱网关（可空）。用 {@link ObjectProvider} 而非直接注入：Bean 由
     * {@code enabled=true} 条件装配，{@code enabled=false} 时容器里没有它，
     * 直接注入会让整个 Adapter 装配失败——那等于「沙箱没开，连 mock 都不能用了」。
     */
    private final AlipayGateway sandboxGateway;

    /** 沙箱开关：用于在染色 SANDBOX 但未启用时给出明确错误（FR-241）。 */
    private final boolean sandboxEnabled;

    /**
     * 沙箱 app_id（FR-203 身份校验：通知必须确实是发给本平台的）。
     *
     * <p>未配置为 {@code null} ⇒ 跳过该校验——部分测试与演示装配不关心身份面，
     * 强制要求配置会让它们全部装配失败，而「未配置」与「配置了但对不上」
     * 是两件不同的事（后者必须拒绝）。</p>
     */
    private final String sandboxAppId;

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
        this.sandboxAppId = sandboxProperties.getAppId();
    }

    /** 便捷构造（测试/演示脚本显式指定形态）：无沙箱能力，等价于 spec 028 时期的行为。 */
    public AlipayChannelAdapter(Scenario scenario) {
        this(scenario, null, false);
    }

    /** 供测试直接注入网关（不经过 Spring 条件装配）；不带 app_id 校验。 */
    public AlipayChannelAdapter(Scenario scenario, AlipayGateway sandboxGateway, boolean sandboxEnabled) {
        this(scenario, sandboxGateway, sandboxEnabled, null);
    }

    /** 供测试直接注入网关与 app_id（FR-203 身份校验的可测形态）。 */
    public AlipayChannelAdapter(Scenario scenario, AlipayGateway sandboxGateway,
                                boolean sandboxEnabled, String sandboxAppId) {
        super(scenario, 1500L, false, 1000L);
        this.sandboxGateway = sandboxGateway;
        this.sandboxEnabled = sandboxEnabled;
        this.sandboxAppId = sandboxAppId;
    }

    @Override
    public ChannelPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    // ---- spec 030 / FR-131 / FR-109：能力声明 ----

    /** 沙箱支持真实渠道（FR-131）；其余 mock 渠道维持基类默认 {@code false}。 */
    @Override
    public boolean supportsRealMode() {
        return true;
    }

    /**
     * 支持的场景（FR-131）：<b>沙箱按真实能力收窄</b>，mock 保持全支持。
     *
     * <p>为什么要分模态给：mock 下所有场景都是本地模拟（声明全支持不产生任何后果），
     * 而沙箱下声明了却不支持会让调用方拿到一个打不开的链接。</p>
     *
     * <p>本方法被内核的能力前置校验读取（{@code requireSceneSupported}），
     * 故沙箱路径上的「不支持场景」会在<b>调用渠道之前</b>被拒。</p>
     */
    @Override
    public Set<PaymentScene> supportedScenes() {
        // 沙箱按真实能力收窄；mock 走全集
        return DyeContext.isSandbox() ? SANDBOX_SCENES : DEFAULT_ALL_SCENES;
    }

    /**
     * 真实模式可用性（FR-241 / INV-8）：沙箱网关存在<b>且</b>开关打开。
     *
     * <p>返回 {@code false} 时，染色 SANDBOX 会被内核门控拦下并抛 400，
     * <b>不静默回落 mock</b>——静默回落最坏的结果是「以为在测沙箱、其实钱根本没进真实渠道」。</p>
     */
    @Override
    protected boolean isRealModeEnabled() {
        return sandboxGateway != null && sandboxEnabled;
    }

    // ===================== 回调翻译（渠道 → 平台，FR-202~FR-204 / FR-210） =====================

    /**
     * 把支付宝异步通知的表单报文翻译成平台语义。
     *
     * <p><b>它替代了 {@code AlipayNotifyController} 的解析职责</b>（FR-015）：
     * 迁移前这段逻辑住在支付宝专属 HTTP 端点里，通用回调端点因此完全不认识支付宝。
     * 下沉到插件后，内核只知道 {@link ParsedCallback} 的三件事——哪一笔、什么结论、
     * 渠道声称多少钱——渠道字段名一律不出现在内核。</p>
     *
     * <h3>四步（顺序不可换）</h3>
     * <ol>
     *   <li><b>验签</b>（FR-202）：签名未验证前，报文里任何字段都不可信，
     *       连日志都不该记全。失败抛异常 ⇒ 通用端点转 403 且<b>不触达</b>任何状态推进（INV-10）。</li>
     *   <li><b>身份一致性</b>（FR-203）：{@code app_id} 与本平台配置不符 ⇒ 这条通知不是发给我们的，
     *       拒绝。这是「①签名/身份」段的第二道关，与验签同源——
     *       故失败也归入签名语义，而不是伪装成业务校验失败。</li>
     *   <li><b>定位单据</b>：没有 {@code out_trade_no} 就无法定位，宁可拒绝也不猜
     *       （猜 = 把钱记到别人头上）。</li>
     *   <li><b>翻译</b>：状态映射 + 金额/币种<b>读出来</b>。判定权在内核
     *       （见 {@link ParsedCallback} 的类注释：插件只「读」，内核负责「判」）。</li>
     * </ol>
     *
     * <p><b>本方法不产生副作用</b>：只做翻译，状态推进由内核的收敛链路负责。</p>
     */
    @Override
    public ParsedCallback parseCallback(ChannelCallbackEnvelope envelope) {
        Map<String, String> params = envelope.formParams();

        // ---- ① 验签（FR-202）----
        if (sandboxGateway == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "alipay notify received but no alipay gateway is configured; "
                            + "refusing to trust an unverifiable notification");
        }
        if (!sandboxGateway.verifyNotify(params)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "alipay notify signature verification failed");
        }

        // ---- ② 身份一致性（FR-203）----
        String appId = params.get("app_id");
        if (appId != null && sandboxAppId != null && !sandboxAppId.isBlank()
                && !sandboxAppId.equals(appId)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "app_id mismatch: expected=" + sandboxAppId + " notified=" + appId);
        }

        // ---- ③ 定位单据 ----
        String paymentNo = params.get("out_trade_no");
        if (paymentNo == null || paymentNo.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "alipay notify carries no out_trade_no: cannot locate the payment");
        }

        // ---- ④ 翻译 ----
        ChannelResult result = toChannelResult(params.get("trade_status"), params.get("trade_no"));
        Long notifiedMinor = parseYuan(params.get("total_amount"));
        ParsedCallback.NotifiedAmount amount = notifiedMinor == null
                ? ParsedCallback.NotifiedAmount.UNKNOWN
                : ParsedCallback.NotifiedAmount.of(notifiedMinor, "CNY");
        return new ParsedCallback(paymentNo, result, amount);
    }

    /**
     * 应答体必须<b>恰好</b>是纯文本 {@code success}（FR-206）。
     *
     * <p>支付宝靠字符串精确匹配判断「平台已收到」——多一个引号、一个换行、一层 JSON 包装，
     * 都会被判为未收到而触发反复重推。</p>
     */
    @Override
    public String callbackAckBody() {
        return "success";
    }

    /**
     * 交易状态映射（FR-204 / CB-10）：与查询路径同口径，<b>不臆断</b>。
     *
     * <p>迁移自 {@code AlipayNotifyController#toChannelResult}，逐字同口径——
     * 两条入站路径（表单通知 / 主动查询）对同一状态必须给同一结论。</p>
     */
    private static ChannelResult toChannelResult(String tradeStatus, String channelTransactionId) {
        if (tradeStatus == null) {
            return ChannelResult.businessUnknown("notify without trade_status");
        }
        return switch (tradeStatus) {
            case STATUS_TRADE_SUCCESS, STATUS_TRADE_FINISHED ->
                    ChannelResult.success(channelTransactionId);
            case STATUS_TRADE_CLOSED ->
                    ChannelResult.businessFailure(channelTransactionId, "alipay trade closed");
            // WAIT_BUYER_PAY 及未知状态：**不推进**（买家还没付 ≠ 这笔不会付）
            default -> ChannelResult.businessUnknown(
                    "alipay trade_status=" + tradeStatus + " (not advancing)");
        };
    }

    /**
     * 「元」字符串 → 分（禁 double/float，INV-1）。
     *
     * <p>解析失败返回 {@code null} ⇒ 内核<b>跳过</b>金额校验，而不是放行一个 {@code 0}：
     * 把「没读到」当成「0 元」会制造出一批假差异（FR-210 尾注）。</p>
     */
    private static Long parseYuan(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(yuan).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    // ===================== 三个真实模式原语（原 sandboxXxx） =====================

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
    @Override
    protected ChannelResult doRealCharge(ChargeRequest request) {
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
    @Override
    protected ChannelResult doRealRefund(RefundRequest request) {
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
    @Override
    protected ChannelResult doRealQuery(QueryStatusRequest request) {
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

    /** 商品标题：无 goods 时回落支付单号，保证渠道侧有可读标识（渠道要求 subject 非空）。 */
    private static String subjectOf(ChargeRequest request) {
        return request.goods() == null ? request.paymentNo() : request.goods().title();
    }
}
