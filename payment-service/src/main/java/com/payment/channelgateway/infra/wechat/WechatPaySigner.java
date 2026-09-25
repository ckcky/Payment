package com.payment.channelgateway.infra.wechat;

import com.wechat.pay.java.core.cipher.RSASigner;
import com.wechat.pay.java.core.cipher.Signer;

import java.security.PrivateKey;

/**
 * 微信支付 V3 请求签名器（spec 039 / FR-004 / FR-011）——<b>本 Feature 唯一产生
 * {@code Authorization} 头的地方</b>。
 *
 * <h3>为什么自己拼基串，而不是直接用 SDK 的 {@code WechatPay2Credential#getAuthorization}</h3>
 * 后者的 {@code timestamp} / {@code nonce_str} 由 SDK 内部生成，<b>不可注入</b>——
 * 于是 FR-011 要求的「固定密钥 + 固定 timestamp/nonce ⇒ 断言 Authorization 逐字节一致」
 * 这条<b>签名金标准</b>根本无法成立。本类把「基串构造 + 头装配」拿回自己手里
 * （两处都是纯函数、可钉死），把真正容易写错的 RSA 运算交给 SDK 的 {@link RSASigner}。
 *
 * <p>基串格式由微信 V3 规范固定，<b>五行、每行以 {@code \n} 结尾</b>：</p>
 * <pre>
 * HTTP请求方法\n
 * URL（path + query，<b>不含</b> scheme/host）\n
 * 请求时间戳（秒）\n
 * 请求随机串\n
 * 请求报文主体（GET 为空串）\n
 * </pre>
 *
 * <p>{@code Authorization} 头形如：</p>
 * <pre>
 * WECHATPAY2-SHA256-RSA2048 mchid="…",nonce_str="…",signature="…",timestamp="…",serial_no="…"
 * </pre>
 *
 * <p>签名 = {@code Base64(RSA-SHA256(基串))}，用<b>商户 API 私钥</b>签。</p>
 */
final class WechatPaySigner {

    /** 认证类型（与微信 V3 规范及 SDK {@code notification.Constant.RSA_SIGN_TYPE} 一致）。 */
    static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048";

    private final String merchantId;
    private final String serialNo;
    private final Signer signer;

    /**
     * @param merchantId 商户号（{@code mchid}）
     * @param serialNo   商户 API 证书序列号（{@code serial_no}）
     * @param privateKey 商户 API 私钥
     */
    WechatPaySigner(String merchantId, String serialNo, PrivateKey privateKey) {
        this.merchantId = merchantId;
        this.serialNo = serialNo;
        this.signer = new RSASigner(serialNo, privateKey);
    }

    /**
     * 构造待签名基串（纯函数，便于金标准钉死）。
     *
     * @param method            HTTP 方法（大写，如 {@code POST} / {@code GET}）
     * @param urlPathWithQuery  URL 的 path + query（<b>不含</b> scheme/host；GET 的 query 也参与签名）
     * @param timestamp         时间戳（秒）
     * @param nonce             随机串
     * @param body              报文主体（无体时传 {@code null}，按空串处理）
     */
    static String baseString(String method, String urlPathWithQuery, long timestamp, String nonce, String body) {
        return method + "\n"
                + urlPathWithQuery + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + (body == null ? "" : body) + "\n";
    }

    /**
     * 生成 {@code Authorization} 头。
     *
     * @param method           HTTP 方法（大写）
     * @param urlPathWithQuery URL 的 path + query
     * @param body             报文主体（无体传 {@code null}）
     * @param timestamp        时间戳（秒）
     * @param nonce            随机串
     * @return 可直接写入 {@code Authorization} 请求头的字符串
     */
    String authorization(String method, String urlPathWithQuery, String body, long timestamp, String nonce) {
        String signature = signer.sign(baseString(method, urlPathWithQuery, timestamp, nonce, body)).getSign();
        return SCHEMA + " mchid=\"" + merchantId + "\""
                + ",nonce_str=\"" + nonce + "\""
                + ",signature=\"" + signature + "\""
                + ",timestamp=\"" + timestamp + "\""
                + ",serial_no=\"" + serialNo + "\"";
    }

    String merchantId() {
        return merchantId;
    }

    String serialNo() {
        return serialNo;
    }

    /**
     * 对任意消息做 RSA-SHA256 签名并 Base64 编码。
     *
     * <p>用于 JSAPI 调起支付的 {@code paySign}（基串见 {@link #jsapiBaseString}）——
     * 与请求签名<b>共用同一把商户私钥</b>，只是基串不同。</p>
     */
    String signMessage(String message) {
        return signer.sign(message).getSign();
    }

    /**
     * JSAPI 调起支付的 {@code paySign} 基串（微信规范）：{@code appId\ntimeStamp\nnonceStr\npackage\n}。
     *
     * <p>与请求签名基串（五行）刻意不同——这是微信协议的两套签名，混用会静默产出
     * 前端无法调起的参数。</p>
     */
    static String jsapiBaseString(String appId, String timeStamp, String nonceStr, String packageValue) {
        return appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
    }
}
