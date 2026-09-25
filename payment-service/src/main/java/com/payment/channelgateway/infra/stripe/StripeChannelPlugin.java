package com.payment.channelgateway.infra.stripe;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.core.rpc.TransportCode;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.common.dto.channel.PayCredential;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.application.spi.AbstractChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.application.spi.ParsedCallback;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Stripe 渠道插件（渠道插件化 / STRIPE-04）——<b>首个按微内核范式接入的渠道</b>。
 *
 * <h3>接入成本清单（这就是「插件化」是否成立的判据）</h3>
 * <table border="1">
 *   <caption>新增一个渠道实际改动的文件</caption>
 *   <tr><th>#</th><th>文件</th><th>是否触碰内核</th></tr>
 *   <tr><td>1</td><td>{@code infra/channel/stripe/*}（5 个类）</td><td>否</td></tr>
 *   <tr><td>2</td><td>{@code pom.xml}（SDK 依赖）</td><td>否</td></tr>
 *   <tr><td>3</td><td>{@code application.yml}（配置段）</td><td>否</td></tr>
 * </table>
 * <b>{@code application/**}、{@code api/**}、{@code domain/**} 零改动</b>——
 * 上游（Payment 编排层）完全感知不到新渠道的存在，这正是本次改造要证明的事情。</p>
 *
 * <h3>Stripe 与支付宝的三处协议差异（全部关在本类内）</h3>
 * <ol>
 *   <li><b>没有 per-request notify_url</b>：支付宝每次下单都传回调地址，Stripe 只有一个
 *       全局 webhook 端点，靠 event body 里的 {@code metadata.paymentNo} 反查单据。
 *       ⇒ 内核的通用回调端点按 {@code {channelCode}} 寻址，不按 URL 区分渠道。</li>
 *   <li><b>验签对象不同</b>：支付宝验签的是「剔除 sign 后的参数表」，Stripe 验签的是
 *       {@code timestamp + "." + rawBody}。⇒ 内核把<b>原始字节</b>原样透传，
 *       不做任何重新序列化（{@code ChannelCallbackEnvelope} 的注释）。</li>
 *   <li><b>凭证形态不同</b>：支付宝 {@code page.pay} 返回自动提交<b>表单 HTML</b>，
 *       Stripe Checkout 返回<b>可直跳的 URL</b>。⇒ 由 {@link PayCredential.Kind} 区分，
 *       消费端判据是枚举而不是字符串嗅探。</li>
 * </ol>
 *
 * <p><b>主流程（能力校验 → 模态门控 → 模态分派 → 异常兜底）由
 * {@link AbstractChannelPlugin} 固定</b>，本类只填三个原语 + 自描述 + 回调翻译。</p>
 */
public class StripeChannelPlugin extends AbstractChannelPlugin {

    /** 渠道码（大写、全局唯一；与 {@code payment_attempts.channel_code} 取值一致）。 */
    public static final String CODE = "STRIPE";

    /** 回调挂载路径段：{@code POST /internal/channels/STRIPE/callback}。 */
    public static final String CALLBACK_PATH = "STRIPE";

    /** Stripe 签名头（小写，与 {@code ChannelCallbackEnvelope} 的约定一致）。 */
    static final String SIGNATURE_HEADER = "stripe-signature";

    /** 会话默认有效期（未显式传入 {@code expireAt} 时）。 */
    private static final long DEFAULT_EXPIRE_MINUTES = 30L;

    /**
     * 支持的场景（按真实能力<b>收窄</b>，FR-131）。
     *
     * <p>Stripe Checkout 是<b>托管收银台</b>：PC 与移动浏览器都跳同一个 hosted page，
     * 故 {@link PaymentScene#WEB} / {@link PaymentScene#H5} 都成立；
     * 而 NATIVE / JSAPI / MINI_PROGRAM / APP 需要各自的集成方式，本插件<b>未实现</b>——
     * 声明支持却拿不到凭证，调用方会以为能付款而买家打开是错误页，故必须收窄。</p>
     */
    private static final Set<PaymentScene> SUPPORTED_SCENES = Set.of(PaymentScene.WEB, PaymentScene.H5);

    /** 插件自描述（包内可见，供 {@link StripeChannelPluginFactory} 不创建实例即可读到）。 */
    static final ChannelPluginDescriptor DESCRIPTOR = new ChannelPluginDescriptor(
            CODE, "Stripe Checkout（test mode）", SUPPORTED_SCENES, true, CALLBACK_PATH);

    private final StripeGateway gateway;
    private final StripeSandboxProperties properties;

    public StripeChannelPlugin(StripeGateway gateway, StripeSandboxProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public ChannelPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected boolean isRealModeEnabled() {
        return properties != null && properties.isEnabled();
    }

    // ===================== 三个渠道原语 =====================

    /**
     * 建单：创建 Checkout Session，返回「买家去哪儿付款」的<b>跳转 URL</b>。
     *
     * <p>语义同支付宝 {@code page.pay}：{@code accepted} = <b>渠道受理 ≠ 买家已付款</b>，
     * payment MUST 停在 {@code PROCESSING}（INV-6：不记账、不通知）。</p>
     *
     * <p>渠道受理流水号取 <b>session id</b>（{@code cs_test_...}）而非 PaymentIntent id——
     * 买家未付款时 Stripe 根本不创建 PaymentIntent，此时只有 session id 是稳定的。
     * 退款时再由 session id 反查 PaymentIntent（见 {@link #doRealRefund}）。</p>
     */
    @Override
    protected ChannelResult doRealCharge(ChargeRequest request) {
        Instant expiresAt = request.expireAt() == null
                ? Instant.now().plus(DEFAULT_EXPIRE_MINUTES, ChronoUnit.MINUTES)
                : request.expireAt();

        StripeGateway.CheckoutResult result = gateway.createCheckout(
                request.paymentNo(),
                request.amountMinor(),
                request.currencyCode(),
                subjectOf(request),
                successUrlOf(request),
                properties.getCancelUrl(),
                expiresAt);

        if (!result.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, result.reason());
        }
        if (result.checkoutUrl() == null || result.checkoutUrl().isBlank()) {
            // 拿到 session 却没有 url：属于渠道侧异常，按「无结论」处理，不臆断成败
            return ChannelResult.businessUnknown("stripe returned no checkout url");
        }
        return ChannelResult.accepted(result.sessionId(), "awaiting buyer",
                PayCredential.redirectUrl(result.checkoutUrl(), expiresAt));
    }

    /**
     * 退款：Stripe 只能对 <b>PaymentIntent</b> 退款，而我们持有的渠道引用是 session id，
     * 故需先回查 session 换出 PaymentIntent id。
     *
     * <p>查不到 PaymentIntent ⇒ 明确失败（不是「受理中」）：没有原交易号就没有退款对象，
     * 这是确定性结论，不该留一笔永久悬挂的退款单。</p>
     */
    @Override
    protected ChannelResult doRealRefund(RefundRequest request) {
        String sessionId = request.channelTransactionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ChannelResult.businessFailure(null,
                    "stripe refund requires channelTransactionId (checkout session id)",
                    BusinessCode.INVALID_REQUEST);
        }
        StripeGateway.CheckoutStatus status = gateway.retrieveCheckout(sessionId);
        if (!status.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, status.reason());
        }
        if (status.paymentIntentId() == null || status.paymentIntentId().isBlank()) {
            return ChannelResult.businessFailure(sessionId,
                    "stripe session has no payment intent: nothing to refund", BusinessCode.DECLINED);
        }

        StripeGateway.RefundOutcome outcome = gateway.refund(status.paymentIntentId(),
                request.refundNo(), request.amountMinor(), request.currencyCode(), request.reason());
        if (!outcome.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, outcome.reason());
        }
        return switch (outcome.state()) {
            case SUCCEEDED -> ChannelResult.success(outcome.refundId());
            case FAILED -> ChannelResult.businessFailure(outcome.refundId(),
                    "stripe refund rejected", BusinessCode.DECLINED);
            // PENDING：渠道受理、结果待定 ⇒ 不臆断，交主动查询/回调收敛
            case PENDING -> ChannelResult.accepted(outcome.refundId(), "stripe refund pending");
        };
    }

    /**
     * 主动查询（webhook 缺失时的<b>权威兜底</b>）：回查 session 的 {@code payment_status}。
     *
     * <p>{@code UNPAID} → {@code businessUnknown}，<b>不臆断为失败</b>：
     * 买家还没付 ≠ 这笔不会付。平台侧应保持待收敛，而不是落 FAILED 把订单关掉
     * （与支付宝 {@code WAIT_BUYER_PAY} 同口径）。</p>
     */
    @Override
    protected ChannelResult doRealQuery(QueryStatusRequest request) {
        String sessionId = request.channelTransactionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ChannelResult.businessUnknown("stripe query requires channelTransactionId (session id)");
        }
        StripeGateway.CheckoutStatus status = gateway.retrieveCheckout(sessionId);
        if (!status.transportOk()) {
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR, status.reason());
        }
        return switch (status.payStatus()) {
            case PAID -> ChannelResult.success(
                    status.paymentIntentId() == null ? sessionId : status.paymentIntentId());
            case NO_PAYMENT_REQUIRED -> ChannelResult.success(sessionId);
            case UNPAID, UNKNOWN -> ChannelResult.businessUnknown(
                    "stripe payment_status=" + status.payStatus().name().toLowerCase());
        };
    }

    // ===================== 回调翻译（渠道 → 平台） =====================

    /**
     * 把 Stripe webhook 报文翻译成平台语义。
     *
     * <p><b>验签在 {@code gateway.verifyAndRead} 内完成</b>——签名未验证前，报文里的
     * 任何字段都不可信。验签失败由网关抛 {@link BizException}，此处的代码<b>不会</b>被执行，
     * 因此不会产生任何状态推进（INV-10）。</p>
     */
    @Override
    public ParsedCallback parseCallback(ChannelCallbackEnvelope envelope) {
        String signature = envelope.headers().get(SIGNATURE_HEADER);
        if (signature == null || signature.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "missing " + SIGNATURE_HEADER + " header: unsigned stripe webhook cannot be trusted");
        }
        StripeGateway.StripeEventView event = gateway.verifyAndRead(envelope.rawBody(), signature);

        String paymentNo = event.metadata() == null ? null : event.metadata().get(StripeSdkGateway.METADATA_PAYMENT_NO);
        if (paymentNo == null || paymentNo.isBlank()) {
            // 没有 paymentNo 就无法定位单据：宁可拒绝，也不猜（猜 = 把钱记到别人头上）
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "stripe event '" + event.eventType() + "' carries no metadata." + StripeSdkGateway.METADATA_PAYMENT_NO);
        }

        ChannelResult result = mapEvent(event);
        ParsedCallback.NotifiedAmount amount = event.amountTotalMinor() == null || event.currency() == null
                ? ParsedCallback.NotifiedAmount.UNKNOWN
                : ParsedCallback.NotifiedAmount.of(event.amountTotalMinor(),
                        event.currency().toUpperCase());
        return new ParsedCallback(paymentNo, result, amount);
    }

    /** Stripe 应答 200 即可，body 内容不参与判定（与支付宝的字符串精确匹配不同）。 */
    @Override
    public String callbackAckBody() {
        return "received";
    }

    private ChannelResult mapEvent(StripeGateway.StripeEventView event) {
        if (event.eventType() == null) {
            return ChannelResult.businessUnknown("stripe event without type");
        }
        return switch (event.eventType()) {
            case StripeSdkGateway.EVENT_CHECKOUT_COMPLETED, StripeSdkGateway.EVENT_CHECKOUT_ASYNC_SUCCEEDED ->
                    "paid".equals(event.payStatus())
                            ? ChannelResult.success(event.sessionId())
                            // completed 但 payment_status 不是 paid：不臆断成功
                            : ChannelResult.businessUnknown(
                                    "stripe session completed but payment_status=" + event.payStatus());
            case StripeSdkGateway.EVENT_CHECKOUT_ASYNC_FAILED ->
                    ChannelResult.businessFailure(event.sessionId(),
                            "stripe async payment failed", BusinessCode.DECLINED);
            case StripeSdkGateway.EVENT_CHECKOUT_EXPIRED ->
                    ChannelResult.businessFailure(event.sessionId(),
                            "stripe checkout session expired (buyer did not pay in time)",
                            BusinessCode.DECLINED);
            // 未订阅 / 未知事件：不推进（例如 charge.refunded 由退款链路单独处理）
            default -> ChannelResult.businessUnknown(
                    "stripe event '" + event.eventType() + "' not advancing");
        };
    }

    private String successUrlOf(ChargeRequest request) {
        if (request.callbackUrls() != null && request.callbackUrls().returnUrl() != null
                && !request.callbackUrls().returnUrl().isBlank()) {
            return request.callbackUrls().returnUrl();
        }
        return properties.getSuccessUrl();
    }

    private static String subjectOf(ChargeRequest request) {
        return request.goods() == null ? request.paymentNo() : request.goods().title();
    }

    /** 供测试与运维面查看配置摘要（不含密钥，INV-2）。 */
    public Map<String, Object> configSummary() {
        return Map.of("code", CODE, "enabled", isRealModeEnabled(),
                "scenes", SUPPORTED_SCENES, "callbackPath", CALLBACK_PATH);
    }
}
