package com.payment.channelgateway.infra.stripe;

import java.time.Instant;
import java.util.Map;

/**
 * Stripe 网关端口（渠道插件化 / STRIPE-01）。
 *
 * <p><b>存在的唯一理由：把 stripe-java SDK 关进笼子</b>——与 {@code AlipayGateway} 同源的设计
 * （INV-7 / ADR-0076）。SDK 是第三方依赖，它的类名、异常体系、返回结构随版本变化；
 * 让它扩散到插件之外的任何一层，SDK 的升级节奏就会绑架平台代码。收口后，将来换成
 * 「纯 JDK {@code HttpClient} + 自签 HMAC」只需新写一个实现类——<b>零扩散</b>。</p>
 *
 * <h3>为什么本端口可以出现 Stripe 概念（{@code session} / {@code payment_intent}）</h3>
 * 本接口位于 {@code infra.channel.stripe}——<b>渠道私有包</b>，不是内核。
 * 分层口径是：<b>内核只认平台概念</b>（{@code ChannelResult} / {@code ParsedCallback}），
 * <b>渠道包内可以认渠道概念</b>。Stripe 概念 → 平台概念的翻译由
 * {@code StripeChannelPlugin} 完成，翻译关系不反向渗透到 {@code application/**}。</p>
 *
 * <h3>金额口径（重要）</h3>
 * Stripe 一律用<b>最小货币单位</b>（{@code unit_amount}，如 USD 的美分、CNY 的分）；
 * 平台 {@code amountMinor} 同为最小货币单位，故可直接透传。
 * <b>已知边界</b>：零小数位币种（JPY / KRW 等）的「最小单位 = 1 元」与本平台
 * 「分」口径不同，接这类币种需要显式换算——当前<b>不支持</b>，插件会在币种不在
 * 白名单时拒绝，而不是静默算错（Constitution §V：金额错误优先于功能可用）。</p>
 */
public interface StripeGateway {

    /**
     * 创建 Checkout Session（托管收银台），返回「买家去哪儿付款」的跳转地址。
     *
     * @param paymentNo   平台支付单号（PM+雪花），写入 {@code metadata}，供回调反查单据
     * @param amountMinor 金额（最小货币单位）
     * @param currency    币种（ISO-4217；Stripe 要求<b>小写</b>，由实现归一化）
     * @param subject     商品名称（展示给买家）
     * @param successUrl  付款完成后 Stripe 跳回的地址（<b>不承载资金事实</b>，同 returnUrl）
     * @param cancelUrl   买家取消时跳回的地址
     * @param expiresAt   会话过期时间（过期链接应失效）
     */
    CheckoutResult createCheckout(String paymentNo, long amountMinor, String currency,
                                  String subject, String successUrl, String cancelUrl, Instant expiresAt);

    /** 按 session id 回查支付状态（webhook 缺失时的<b>权威兜底</b>路径）。 */
    CheckoutStatus retrieveCheckout(String sessionId);

    /** 对原 PaymentIntent 退款。 */
    RefundOutcome refund(String paymentIntentId, String refundNo, long amountMinor,
                         String currency, String reason);

    /**
     * 验签并读取事件（<b>回调三段式校验的第①段</b>，ADR-0025 / FR-202）。
     *
     * <p>验签<b>必须</b>用原始字节：{@code Webhook.constructEvent} 的签名基串是
     * {@code timestamp + "." + rawBody}，重新序列化 JSON 会改变基串导致必然失败。
     * 验签失败 MUST 抛 {@code BizException} ⇒ 由端点转 403，<b>不触达</b>状态推进。</p>
     */
    StripeEventView verifyAndRead(String rawBody, String signatureHeader);

    // ===================== 结果类型（Stripe 语义，不含 SDK 类型） =====================

    /** 建单结果。 */
    record CheckoutResult(String sessionId, String checkoutUrl, boolean transportOk, String reason) {

        public static CheckoutResult ok(String sessionId, String checkoutUrl) {
            return new CheckoutResult(sessionId, checkoutUrl, true, null);
        }

        public static CheckoutResult transportFailure(String reason) {
            return new CheckoutResult(null, null, false, reason);
        }
    }

    /** Checkout Session 的支付状态。 */
    enum PayStatus {
        /** 已付款（{@code payment_status=paid}）——权威成功。 */
        PAID,
        /** 未付款（{@code unpaid}）：买家还没付。<b>不等于失败</b>，不臆断。 */
        UNPAID,
        /** 无需付款（金额为 0 等）。 */
        NO_PAYMENT_REQUIRED,
        /** 无法识别 / 响应不完整——<b>无结论</b>。 */
        UNKNOWN
    }

    /** 查询结果。 */
    record CheckoutStatus(PayStatus payStatus, String paymentIntentId, Long amountTotalMinor,
                          String currency, boolean transportOk, String reason) {

        public static CheckoutStatus of(PayStatus status, String paymentIntentId,
                                        Long amountTotalMinor, String currency, String reason) {
            return new CheckoutStatus(status, paymentIntentId, amountTotalMinor, currency, true, reason);
        }

        public static CheckoutStatus transportFailure(String reason) {
            return new CheckoutStatus(PayStatus.UNKNOWN, null, null, null, false, reason);
        }
    }

    /** 退款状态。 */
    enum RefundState {
        SUCCEEDED, PENDING, FAILED
    }

    /** 退款结果。 */
    record RefundOutcome(String refundId, RefundState state, boolean transportOk, String reason) {

        public static RefundOutcome of(String refundId, RefundState state, String reason) {
            return new RefundOutcome(refundId, state, true, reason);
        }

        public static RefundOutcome transportFailure(String reason) {
            return new RefundOutcome(null, RefundState.PENDING, false, reason);
        }
    }

    /**
     * 已验签事件的<b>只读投影</b>。
     *
     * <p>刻意不把 {@code com.stripe.model.Event} / {@code Session} 交给插件：
     * 那样插件就必须 import SDK，收口失效。插件只见本记录（纯平台类型）。</p>
     *
     * @param eventType  事件类型（{@code checkout.session.completed} 等）
     * @param sessionId  Checkout Session id（{@code cs_test_...}）——渠道受理流水号
     * @param paymentIntentId PaymentIntent id（可能为 {@code null}：未付款时 Stripe 不创建 PI）
     * @param payStatus  支付状态原文
     * @param metadata   建单时写入的元数据（含 {@code paymentNo}）
     * @param amountTotalMinor 会话总金额（最小单位）
     * @param currency   币种
     */
    record StripeEventView(String eventType, String sessionId, String paymentIntentId,
                           String payStatus, Map<String, String> metadata,
                           Long amountTotalMinor, String currency) {
    }
}
