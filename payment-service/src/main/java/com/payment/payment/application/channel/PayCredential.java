package com.payment.payment.application.channel;

import java.time.Instant;
import java.util.Objects;

/**
 * 付款凭证（渠道 → 平台，spec 030 / FR-111）：「怎么把买家带去付款」的载体。
 *
 * <p>真实渠道下单成功后不会立刻收到钱，而是返回一个让买家完成付款的<b>凭证</b>
 * （跳转 URL / 表单 HTML / 二维码串 / JSAPI 参数 / client_secret）。
 * 修复前的 {@link ChannelResult} <b>没有凭证载体</b>，真实渠道闭环无处承载——本类型即为此而设。</p>
 *
 * <h3>安全约束（INV-2，硬约束）</h3>
 * <ul>
 *   <li>{@code payload} <b>MUST NOT 入库</b>（{@code payment_attempts} 不新增凭证列）；</li>
 *   <li><b>MUST NOT</b> 进 git（禁止写进测试夹具或文档示例）；</li>
 *   <li><b>MUST NOT</b> 进明文日志（禁止打印完整凭证）。</li>
 * </ul>
 * 持久化渠道标识恒为 {@code payment_attempts.channel_reference}。
 *
 * <h3>为何 {@link Kind#REDIRECT_URL} 与 {@link Kind#H5_URL} 刻意不合并</h3>
 * 二者下游<b>消费方式不同</b>：{@code REDIRECT_URL} 是「浏览器 302 跳转」，
 * {@code H5_URL} 是「在 App WebView / 容器内打开或唤起」。合并会让消费端靠
 * 字符串猜语义——这是要消灭的反模式（类比：禁止解析渠道引用字符串还原模态）。
 */
public record PayCredential(Kind kind, String payload, Instant expiresAt) {

    public PayCredential {
        Objects.requireNonNull(kind, "credential.kind");
        Objects.requireNonNull(payload, "credential.payload");
    }

    /** 凭证形态。 */
    public enum Kind {
        /** 浏览器跳转 URL（支付宝 pagePay GET 签名 URL、微信 MWEB 等）。 */
        REDIRECT_URL,
        /** 自动提交的表单 HTML（部分渠道返回整段 form）。 */
        FORM_HTML,
        /** 二维码内容串（NATIVE 扫码场景，需消费端自行渲染成二维码图）。 */
        QR_CODE,
        /** H5 地址（App WebView / 容器内打开或唤起，与 REDIRECT_URL 消费方式不同）。 */
        H5_URL,
        /** JSAPI 调起支付所需的参数包（JSON 串，供前端 SDK 使用）。 */
        JSAPI_PARAMS,
        /** 客户端密钥（Stripe PaymentIntent client_secret 等，供前端 SDK 确认支付）。 */
        CLIENT_SECRET
    }

    /** 跳转类凭证工厂（REDIRECT_URL）。 */
    public static PayCredential redirectUrl(String url, Instant expiresAt) {
        return new PayCredential(Kind.REDIRECT_URL, url, expiresAt);
    }

    /** H5 凭证工厂。 */
    public static PayCredential h5Url(String url, Instant expiresAt) {
        return new PayCredential(Kind.H5_URL, url, expiresAt);
    }

    /** 二维码凭证工厂。 */
    public static PayCredential qrCode(String content, Instant expiresAt) {
        return new PayCredential(Kind.QR_CODE, content, expiresAt);
    }

    /**
     * 是否属于「跳转家族」（{@link Kind#REDIRECT_URL} 或 {@link Kind#H5_URL}）。
     *
     * <p>消费端据此决定是否可直接 {@code window.open(payload)}；
     * 非跳转家族（二维码 / JSAPI / client_secret）需要各自的渲染或 SDK 处理，
     * 直接打开会出错。</p>
     */
    public boolean isRedirectFamily() {
        return kind == Kind.REDIRECT_URL || kind == Kind.H5_URL;
    }
}
