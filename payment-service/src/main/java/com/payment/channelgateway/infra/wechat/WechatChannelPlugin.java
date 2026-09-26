package com.payment.channelgateway.infra.wechat;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.application.spi.AbstractChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.core.rpc.TransportCode;
import com.payment.common.dto.channel.PayCredential;
import com.payment.common.dto.channel.PaymentScene;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 微信支付渠道插件（spec 039 / FR-001 / FR-003）——按微内核范式接入的第三个真实渠道。
 *
 * <h3>接入成本清单（「插件化」是否成立的判据）</h3>
 * <table border="1">
 *   <caption>新增微信渠道实际改动的文件</caption>
 *   <tr><th>#</th><th>文件</th><th>是否触碰内核</th></tr>
 *   <tr><td>1</td><td>{@code infra/wechat/*}（5 个类）</td><td>否</td></tr>
 *   <tr><td>2</td><td>{@code pom.xml}（SDK 依赖）</td><td>否</td></tr>
 *   <tr><td>3</td><td>{@code application.yml}（配置段）</td><td>否</td></tr>
 *   <tr><td>4</td><td>删除旧 {@code infra/WechatChannelAdapter}</td><td>否</td></tr>
 * </table>
 * <b>{@code application/**}、{@code api/**}、{@code domain/**}、SPI 内核零改动</b>（INV-6）。</p>
 *
 * <h3>注册语义（C-1 裁决 / FR-016）</h3>
 * 本插件<b>始终注册</b>（与 Stripe 同构）：{@code enabled} 只门控真实模式
 * （{@link #isRealModeEnabled()}），<b>不</b>门控注册。故 {@code enabled=false} 时
 * WECHAT 仍出现在 {@code GET /internal/channels}（{@code enabled:false}），MOCK 模态照常可用。
 * <b>MUST NOT</b> 给本类或其工厂加 {@code @ConditionalOnProperty}。</p>
 *
 * <h3>主流程由内核固定</h3>
 * 「能力校验 → 模态门控 → 模态分派 → 异常兜底」四步在
 * {@link AbstractChannelPlugin} 里 {@code final}，本类只填
 * {@link #descriptor()} + {@link #isRealModeEnabled()} + 三个 {@code doRealXxx} + 回调翻译。
 * <b>MOCK 语义完全交给内核</b>（FR-010 / D7）——插件内不自写 mock。</p>
 */
public class WechatChannelPlugin extends AbstractChannelPlugin {

    /** 渠道码（大写、全局唯一；与 {@code channel_orders.channel_code} 取值一致）。 */
    public static final String CODE = "WECHAT";

    /** 回调挂载路径段：{@code POST /internal/channels/WECHAT/callback}（复用通用端点，FR-014）。 */
    public static final String CALLBACK_PATH = "WECHAT";

    /** 微信 V3 通知签名头（小写，与 {@code ChannelCallbackEnvelope} 的约定一致）。 */
    static final String HEADER_SIGNATURE = "wechatpay-signature";
    static final String HEADER_TIMESTAMP = "wechatpay-timestamp";
    static final String HEADER_NONCE = "wechatpay-nonce";
    static final String HEADER_SERIAL = "wechatpay-serial";

    /** 会话默认有效期（未显式传入 {@code expireAt} 时）。 */
    private static final long DEFAULT_EXPIRE_MINUTES = 30L;

    /**
     * 支持的场景（FR-003）。
     *
     * <p>NATIVE（扫码）/ JSAPI（公众号）/ MINI_PROGRAM（小程序）/ H5（手机网站）。
     * <b>不声明</b> {@link PaymentScene#WEB} 与 {@link PaymentScene#APP}——
     * 微信无对应的本插件已实现端点，声明支持却拿不到凭证，调用方会以为能付款而买家打开是错误页，
     * 故按真实能力收窄。</p>
     */
    private static final Set<PaymentScene> SUPPORTED_SCENES = Set.of(
            PaymentScene.NATIVE, PaymentScene.JSAPI, PaymentScene.MINI_PROGRAM, PaymentScene.H5);

    /** 插件自描述（包内可见，供 {@link WechatChannelPluginFactory} 不创建实例即可读到）。 */
    static final ChannelPluginDescriptor DESCRIPTOR = new ChannelPluginDescriptor(
            CODE, "微信支付 V3（Native 扫码 / JSAPI / 小程序 / H5）", SUPPORTED_SCENES, true, CALLBACK_PATH);

    private final WechatGateway gateway;
    private final WechatPayProperties properties;

    public WechatChannelPlugin(WechatGateway gateway, WechatPayProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public ChannelPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * 真实模式是否可用（C-1 裁决 / FR-016）：<b>只</b>读 {@code enabled}。
     *
     * <p>注意本方法<b>不</b>影响渠道注册——{@code enabled=false} 时本插件依然在注册表里，
     * 只是染色 SANDBOX 会被内核 400 挡下（FR-241），未染色则走内核统一 MOCK。</p>
     */
    @Override
    protected boolean isRealModeEnabled() {
        return properties != null && properties.isEnabled();
    }

    // ===================== 三个渠道原语 =====================

    /**
     * 建单：调统一下单，返回「买家怎么付款」的<b>凭证</b>。
     *
     * <p>语义：{@code accepted} = <b>渠道受理 ≠ 买家已付款</b>，payment MUST 停在
     * {@code PROCESSING}（INV-6：不记账、不通知）。</p>
     */
    @Override
    protected ChannelResult doRealCharge(ChargeRequest request) {
        PaymentScene scene = request.scene();
        if (scene == null) {
            // 场景决定端点与凭证形态，缺了就无从下单——宁可明确拒绝，也不猜一个默认端点
            return ChannelResult.businessFailure(null,
                    "wechat charge requires an explicit scene (NATIVE/JSAPI/MINI_PROGRAM/H5)",
                    BusinessCode.INVALID_REQUEST);
        }
        String notifyUrl = notifyUrlOf();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            // 回调地址是资金事实的唯一权威来源；缺失即「下单成功却收不到钱的通知」（INV-8 同口径）
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat charge requires notifyUrl; returnUrl does not carry funds facts");
        }
        String openid = request.payer() == null ? null : request.payer().payerId();
        if ((scene == PaymentScene.JSAPI || scene == PaymentScene.MINI_PROGRAM)
                && (openid == null || openid.isBlank())) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat " + scene + " charge requires payer.payerId (openid)");
        }

        Instant expiresAt = request.expireAt() == null
                ? Instant.now().plus(DEFAULT_EXPIRE_MINUTES, ChronoUnit.MINUTES)
                : request.expireAt();
        String clientIp = request.payer() == null ? null : request.payer().clientIp();

        WechatGateway.PrepayResult result = gateway.prepay(new WechatGateway.PrepayCommand(
                scene, request.paymentNo(), request.amountMinor(), request.currencyCode(),
                subjectOf(request), notifyUrl, openid, clientIp, expiresAt));

        if (!result.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.errorMessage());
        }
        if (!result.accepted()) {
            return ChannelResult.businessFailure(null,
                    "wechat rejected prepay: " + result.errorCode() + " " + safe(result.errorMessage()),
                    mapErrorCode(result.errorCode()));
        }
        return ChannelResult.accepted(null, "awaiting buyer",
                credentialOf(scene, result.prepayKey(), expiresAt));
    }

    /**
     * 退款：{@code out_refund_no} 用平台 {@code refundNo} 直接映射。
     *
     * <p><b>退款金额 = 原订单总金额</b>：ADR-0016 已否决部分退款（恒按全退处理），
     * 故微信要求的 {@code amount.total} 与 {@code amount.refund} 同值。
     * 若将来支持部分退款，此处必须改为从原支付读取总额——否则微信会以
     * 「total 与 refund 不一致」拒绝，而不是静默少退。</p>
     */
    @Override
    protected ChannelResult doRealRefund(RefundRequest request) {
        String channelTxn = request.channelTransactionId();
        if ((channelTxn == null || channelTxn.isBlank())
                && (request.paymentNo() == null || request.paymentNo().isBlank())) {
            return ChannelResult.businessFailure(null,
                    "wechat refund requires either channelTransactionId or paymentNo to locate the original trade",
                    BusinessCode.INVALID_REQUEST);
        }
        WechatGateway.RefundResult result = gateway.refund(new WechatGateway.RefundCommand(
                request.paymentNo(), channelTxn, request.refundNo(),
                request.amountMinor(), request.amountMinor(), request.currencyCode(),
                request.reason(), request.refundNotifyUrl()));

        if (!result.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.errorMessage());
        }
        if (result.errorCode() != null) {
            return ChannelResult.businessFailure(null,
                    "wechat refund rejected: " + result.errorCode() + " " + safe(result.errorMessage()),
                    mapErrorCode(result.errorCode()));
        }
        return switch (result.state()) {
            case SUCCESS -> ChannelResult.success(result.refundId());
            case CLOSED, ABNORMAL -> ChannelResult.businessFailure(result.refundId(),
                    "wechat refund state=" + result.state(), BusinessCode.DECLINED);
            // PROCESSING / UNKNOWN：渠道受理、结果待定 ⇒ 不臆断，交主动查询/回调收敛
            case PROCESSING, UNKNOWN -> ChannelResult.accepted(result.refundId(),
                    "wechat refund " + result.state());
        };
    }

    /**
     * 主动查询（回调缺失时的<b>权威兜底</b>）：有渠道交易号则按 {@code transaction_id} 查，
     * 否则按 {@code out_trade_no}（FR-005 的两种键）。
     *
     * <p>{@code NOTPAY} / {@code USERPAYING} → {@code businessUnknown}，<b>不臆断为失败</b>：
     * 买家还没付 ≠ 这笔不会付（INV-7）。</p>
     */
    @Override
    protected ChannelResult doRealQuery(QueryStatusRequest request) {
        String channelTxn = request.channelTransactionId();
        WechatGateway.QueryResult result = channelTxn != null && !channelTxn.isBlank()
                ? gateway.queryByTransactionId(channelTxn)
                : gateway.queryByOutTradeNo(request.paymentNo());

        if (!result.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.errorMessage());
        }
        if (result.errorCode() != null) {
            return ChannelResult.businessFailure(null,
                    "wechat query error: " + result.errorCode() + " " + safe(result.errorMessage()),
                    mapErrorCode(result.errorCode()));
        }
        String reference = result.transactionId() == null ? request.paymentNo() : result.transactionId();
        return switch (result.tradeState()) {
            case SUCCESS -> ChannelResult.success(reference);
            case REFUND, CLOSED, REVOKED, PAYERROR -> ChannelResult.businessFailure(reference,
                    "wechat trade_state=" + result.tradeState(), BusinessCode.DECLINED);
            case NOTPAY, USERPAYING -> ChannelResult.businessUnknown(
                    "wechat trade_state=" + result.tradeState());
            case UNKNOWN -> ChannelResult.businessUnknown("wechat trade_state unrecognized");
        };
    }

    // ===================== 回调翻译（渠道 → 平台，FR-007） =====================

    /**
     * 把微信 V3 通知翻译成平台语义。
     *
     * <p><b>验签与解密在 {@code gateway.verifyAndDecrypt} 内完成，且顺序不可颠倒</b>
     * （INV-5）：签名未验证前，报文里的任何字段都不可信。失败由网关抛
     * {@link BizException}，此处的代码<b>不会</b>被执行，因此不会产生任何状态推进（INV-10）。</p>
     */
    @Override
    public ParsedCallback parseCallback(ChannelCallbackEnvelope envelope) {
        Map<String, String> headers = envelope.headers();
        String signature = headers.get(HEADER_SIGNATURE);
        String timestamp = headers.get(HEADER_TIMESTAMP);
        String nonce = headers.get(HEADER_NONCE);
        String serial = headers.get(HEADER_SERIAL);
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce) || isBlank(serial)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "missing wechatpay signature headers: an unsigned notification cannot be trusted");
        }

        WechatGateway.NotificationResult notification = gateway.verifyAndDecrypt(
                new WechatGateway.NotificationEnvelope(timestamp, nonce, signature, serial, envelope.rawBody()));

        String paymentNo = notification.outTradeNo();
        if (isBlank(paymentNo)) {
            // 没有 out_trade_no 就无法定位单据：宁可拒绝，也不猜（猜 = 把钱记到别人头上）
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat notification carries no out_trade_no; refusing to guess the payment");
        }

        ChannelResult result = mapNotificationResult(notification);
        ParsedCallback.NotifiedAmount amount =
                notification.amountTotalMinor() == null || notification.currency() == null
                        ? ParsedCallback.NotifiedAmount.UNKNOWN
                        : ParsedCallback.NotifiedAmount.of(notification.amountTotalMinor(),
                                notification.currency().toUpperCase(Locale.ROOT));
        return new ParsedCallback(paymentNo, result, amount);
    }

    /** 微信应答体：`{"code":"SUCCESS","message":"成功"}` 才算「平台已收到」。 */
    @Override
    public String callbackAckBody() {
        return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    }

    private ChannelResult mapNotificationResult(WechatGateway.NotificationResult notification) {
        String reference = notification.transactionId();
        String raw = notification.tradeState();
        if (raw == null) {
            return ChannelResult.businessUnknown("wechat notification without trade_state");
        }
        return switch (WechatSdkGateway.mapTradeState(raw)) {
            case SUCCESS -> ChannelResult.success(reference);
            case REFUND, CLOSED, REVOKED, PAYERROR -> ChannelResult.businessFailure(reference,
                    "wechat trade_state=" + raw, BusinessCode.DECLINED);
            case NOTPAY, USERPAYING -> ChannelResult.businessUnknown("wechat trade_state=" + raw);
            case UNKNOWN -> ChannelResult.businessUnknown("wechat trade_state unrecognized: " + raw);
        };
    }

    // ===================== 辅助 =====================

    /** 凭证形态按场景区分（消费端据 {@link PayCredential.Kind} 渲染，不做字符串嗅探）。 */
    private PayCredential credentialOf(PaymentScene scene, String prepayKey, Instant expiresAt) {
        return switch (scene) {
            case NATIVE -> PayCredential.qrCode(prepayKey, expiresAt);
            case H5 -> PayCredential.h5Url(prepayKey, expiresAt);
            case JSAPI, MINI_PROGRAM -> new PayCredential(PayCredential.Kind.JSAPI_PARAMS,
                    gateway.jsapiPayParams(prepayKey), expiresAt);
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat plugin does not support scene " + scene);
        };
    }

    /**
     * 微信业务错误码 → 平台 {@link BusinessCode}。
     *
     * <p>只映射<b>语义明确</b>的几个码；其余一律 {@link BusinessCode#DECLINED}——
     * 宁可笼统地「被拒绝」，也不把微信错误码原文泄漏进平台语义（渠道差异必须关在插件内）。</p>
     */
    static BusinessCode mapErrorCode(String wechatCode) {
        if (wechatCode == null) {
            return BusinessCode.DECLINED;
        }
        return switch (wechatCode) {
            case "NOTENOUGH" -> BusinessCode.INSUFFICIENT_FUNDS;
            case "PARAM_ERROR", "SIGN_ERROR", "APPID_MCHID_NOT_MATCH", "OUT_TRADE_NO_USED" ->
                    BusinessCode.INVALID_REQUEST;
            case "SYSTEMERROR", "FREQUENCY_LIMITED" -> BusinessCode.UNKNOWN;
            default -> BusinessCode.DECLINED;
        };
    }

    /**
     * 异步通知地址（spec 041：读本插件自己的配置）。
     *
     * <p>改造前优先取 {@code ChargeRequest.callbackUrls().notifyUrl()}、回落配置；
     * spec 041 移除该字段后统一读配置——通知地址是微信下单请求自身的参数，
     * 属渠道协议细节。{@code notifyUrl} 是资金事实来源，配置缺失时由下单前的校验拒绝
     * （不静默降级，INV-8）。</p>
     */
    private String notifyUrlOf() {
        return properties == null ? null : properties.getNotifyUrl();
    }

    private static String subjectOf(ChargeRequest request) {
        return request.goods() == null ? request.paymentNo() : request.goods().title();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
