package com.payment.common.dto.channel;

/**
 * 付款人信息（平台 → 渠道，spec 030 / FR-104）。
 *
 * <p>{@link PaymentScene#JSAPI} / {@link PaymentScene#MINI_PROGRAM} 场景通常需要付款人在渠道侧的
 * 标识（微信 {@code openid}、支付宝 {@code buyer_id}）；风控与反欺诈通常需要客户端 IP。
 * 两者按需提供，均<b>可空</b>——不强制收集。</p>
 *
 * @param payerId  付款人在渠道侧的标识（微信 openid / 支付宝 buyer_id 等），可空
 * @param clientIp 客户端 IP，可空
 */
public record Payer(String payerId, String clientIp) {

    /** 只有渠道侧付款人标识。 */
    public static Payer of(String payerId) {
        return new Payer(payerId, null);
    }

    /** 只有客户端 IP（风控 / 反欺诈用）。 */
    public static Payer byClientIp(String clientIp) {
        return new Payer(null, clientIp);
    }
}
