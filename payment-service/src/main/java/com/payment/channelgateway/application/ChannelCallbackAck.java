package com.payment.channelgateway.application;

/**
 * 渠道回调的<b>应答</b>（渠道网关域 → HTTP 适配层，spec 037 / T5 / FR-009）。
 *
 * <p>回调入口把「收报文、交网关」做完之后，只剩一件事：把网关给出的应答转成 HTTP。
 * 本记录就是那一层薄薄的传递物——它刻意只区分「验签过没过」与「body 是什么」，
 * 不把 HTTP 状态码、{@code ResponseEntity} 之类的 Web 类型带进渠道网关域。</p>
 *
 * <h3>为什么只区分两档状态</h3>
 * <p>既有回调入口的应答形态只有两种 HTTP 状态：验签失败 ⇒ <b>403</b>（报文真伪未定，
 * 不触达任何状态推进，INV-10）；其余情况一律 <b>200</b>，靠 body 表达语义
 * （成功应答体 / {@code rejected: …} / {@code processing error}）——因为渠道（如支付宝）
 * 恰恰靠 body 的<b>精确匹配</b>判断「平台已收到」，HTTP 状态反而无所谓。
 * 把 body 的构造权留在网关域，是为了让「渠道协议要求的应答长什么样」这件事
 * 仍然只由渠道插件决定（{@code ChannelPlugin#callbackAckBody}）。</p>
 *
 * @param signatureVerified 验签是否通过（{@code false} ⇒ HTTP 403）
 * @param body              应答体（渠道协议要求的内容）
 */
public record ChannelCallbackAck(boolean signatureVerified, String body) {

    /** 验签通过：HTTP 200 + 给定应答体。 */
    public static ChannelCallbackAck ok(String body) {
        return new ChannelCallbackAck(true, body);
    }

    /** 验签失败：HTTP 403，且不触达状态推进（INV-10）。 */
    public static ChannelCallbackAck rejected(String body) {
        return new ChannelCallbackAck(false, body);
    }
}
