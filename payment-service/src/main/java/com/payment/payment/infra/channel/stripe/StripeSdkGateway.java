package com.payment.payment.infra.channel.stripe;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Stripe 网关的 SDK 实现（渠道插件化 / STRIPE-02）——<b>全仓唯一允许 import {@code com.stripe.*} 的类</b>。
 *
 * <p>与 {@code AlipaySdkGateway} 同源的收口约定（INV-7 / ADR-0076）：
 * 本类是 stripe-java 的<b>唯一出口</b>。插件、内核、应用层一律只见
 * {@link StripeGateway} 的平台/渠道概念，不见 SDK 类型。将来若要把 SDK 换成
 * 纯 JDK 实现，改动范围是<b>这一个类</b>。</p>
 *
 * <h3>异常归一化（FR-141 同口径）</h3>
 * <ul>
 *   <li>SDK 抛 {@link StripeException}（含超时/断连/渠道 4xx）⇒ {@code transportOk=false}
 *       或 {@code 400}——<b>绝不臆断成败</b>；</li>
 *   <li>验签失败 ⇒ {@link BizException}（403），<b>不触达</b>任何状态推进。</li>
 * </ul>
 * 渠道错误码原文<b>MUST NOT</b>出现在返回值里——那等于把 Stripe 协议泄漏进平台语义。
 */
public class StripeSdkGateway implements StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeSdkGateway.class);

    /** 事件类型：Checkout 会话完成（买家已付款）。 */
    static final String EVENT_CHECKOUT_COMPLETED = "checkout.session.completed";
    /** 事件类型：Checkout 会话过期（买家未在有效期内付款）。 */
    static final String EVENT_CHECKOUT_EXPIRED = "checkout.session.expired";
    /** 事件类型：异步支付完成（部分付款方式在会话完成后才最终确认）。 */
    static final String EVENT_CHECKOUT_ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";
    /** 事件类型：异步支付失败。 */
    static final String EVENT_CHECKOUT_ASYNC_FAILED = "checkout.session.async_payment_failed";

    /** 建单时写入 Session 元数据、供回调反查平台支付单的键名。 */
    static final String METADATA_PAYMENT_NO = "paymentNo";

    /**
     * 非零小数位币种白名单（Stripe「最小单位 = 1/100 元」与平台「分」口径一致）。
     *
     * <p>零小数位币种（JPY / KRW / VND …）在 Stripe 的最小单位就是 1 元，
     * 与本平台「分」差 100 倍。与其静默算错，不如明确拒绝——
     * 金额错误优先于功能可用（Constitution §V）。</p>
     */
    private static final java.util.Set<String> TWO_DECIMAL_CURRENCIES = java.util.Set.of(
            "cny", "usd", "eur", "gbp", "hkd", "sgd", "aud", "cad");

    private final StripeSandboxProperties properties;

    public StripeSdkGateway(StripeSandboxProperties properties) {
        this.properties = properties;
        Stripe.apiKey = properties.getSecretKey();
        Stripe.setConnectTimeout((int) properties.getConnectTimeoutMs());
        Stripe.setReadTimeout((int) properties.getReadTimeoutMs());
    }

    @Override
    public CheckoutResult createCheckout(String paymentNo, long amountMinor, String currency,
                                         String subject, String successUrl, String cancelUrl,
                                         Instant expiresAt) {
        String normalized = normalizeCurrency(currency);
        requireTwoDecimalCurrency(normalized);

        try {
            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    // 幂等定位靠 metadata：Stripe 没有 per-request notify_url，
                    // 回调只能靠 event body 里的 id 反查单据——这是与支付宝最大的协议差异
                    .putMetadata(METADATA_PAYMENT_NO, paymentNo)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(normalized)
                                    .setUnitAmount(amountMinor)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(subject == null || subject.isBlank() ? paymentNo : subject)
                                            .build())
                                    .build())
                            .build());
            if (expiresAt != null) {
                builder.setExpiresAt(expiresAt.getEpochSecond());
            }
            Session session = Session.create(builder.build());
            return CheckoutResult.ok(session.getId(), session.getUrl());
        } catch (StripeException e) {
            log.error("stripe checkout session creation failed paymentNo={} err={}", paymentNo, e.getMessage());
            return CheckoutResult.transportFailure("stripe checkout failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public CheckoutStatus retrieveCheckout(String sessionId) {
        try {
            Session session = Session.retrieve(sessionId);
            return CheckoutStatus.of(mapPayStatus(session.getPaymentStatus()),
                    session.getPaymentIntent(), session.getAmountTotal(),
                    session.getCurrency(), "payment_status=" + session.getPaymentStatus());
        } catch (StripeException e) {
            log.error("stripe session retrieval failed sessionId={} err={}", sessionId, e.getMessage());
            return CheckoutStatus.transportFailure("stripe retrieve failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public RefundOutcome refund(String paymentIntentId, String refundNo, long amountMinor,
                                String currency, String reason) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            // 退款是对原支付的操作：没有 PaymentIntent id，Stripe 无法定位原交易
            return RefundOutcome.transportFailure("missing paymentIntentId for refund " + refundNo);
        }
        try {
            RefundCreateParams.Builder builder = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setAmount(amountMinor)
                    .putMetadata("refundNo", refundNo);
            Refund refund = Refund.create(builder.build());
            return RefundOutcome.of(refund.getId(), mapRefundState(refund.getStatus()),
                    "status=" + refund.getStatus());
        } catch (StripeException e) {
            log.error("stripe refund failed refundNo={} err={}", refundNo, e.getMessage());
            return RefundOutcome.transportFailure("stripe refund failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 验签并读取事件。
     *
     * <p><b>验签必须用原始字节</b>：签名基串是 {@code timestamp + "." + rawBody}，
     * 重新序列化 JSON 会改变基串 ⇒ 必然验签失败。这也是内核把 {@code rawBody}
     * 原样透传给插件的原因（{@code ChannelCallbackEnvelope} 的注释）。</p>
     */
    @Override
    public StripeEventView verifyAndRead(String rawBody, String signatureHeader) {
        Session session;
        String eventType;
        try {
            com.stripe.model.Event event =
                    Webhook.constructEvent(rawBody, signatureHeader, properties.getWebhookSecret());
            eventType = event.getType();
            StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
            if (!(object instanceof Session s)) {
                // 不是 Checkout Session 事件：本插件不处理（例如 charge.refunded）
                return new StripeEventView(eventType, null, null, null, Map.of(), null, null);
            }
            session = s;
        } catch (StripeException e) {
            // 验签失败（SignatureVerificationException）与反序列化失败都是「报文不可信」：
            // ⇒ 403，且不触达状态推进（INV-10）。两者对调用方的后果完全一致，故合并处理。
            // ⚠️ 不要拆成两个 catch：SignatureVerificationException 是 StripeException 的子类，
            // 先 catch 子类会让父类分支变成不可达代码（编译告警）。
            log.warn("stripe webhook rejected: {}", e.getMessage());
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "stripe webhook payload not trustworthy");
        }
        return new StripeEventView(eventType, session.getId(), session.getPaymentIntent(),
                session.getPaymentStatus(), session.getMetadata() == null ? Map.of() : session.getMetadata(),
                session.getAmountTotal(), session.getCurrency());
    }

    private static PayStatus mapPayStatus(String raw) {
        if (raw == null) {
            return PayStatus.UNKNOWN;
        }
        return switch (raw) {
            case "paid" -> PayStatus.PAID;
            case "unpaid" -> PayStatus.UNPAID;
            case "no_payment_required" -> PayStatus.NO_PAYMENT_REQUIRED;
            default -> PayStatus.UNKNOWN;
        };
    }

    private static RefundState mapRefundState(String raw) {
        if (raw == null) {
            return RefundState.PENDING;
        }
        return switch (raw) {
            case "succeeded" -> RefundState.SUCCEEDED;
            case "failed", "canceled" -> RefundState.FAILED;
            default -> RefundState.PENDING;
        };
    }

    /** Stripe 要求币种为 ISO-4217 小写。 */
    private static String normalizeCurrency(String currency) {
        return currency == null ? "cny" : currency.trim().toLowerCase();
    }

    private static void requireTwoDecimalCurrency(String normalized) {
        if (!TWO_DECIMAL_CURRENCIES.contains(normalized)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "unsupported currency for stripe: '" + normalized + "'; supported(two-decimal minor unit "
                            + "aligned with platform 'fen'): " + TWO_DECIMAL_CURRENCIES);
        }
    }
}
