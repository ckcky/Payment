package com.payment.payment.infra.channel.stripe;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stripe 沙箱（test mode）配置（渠道插件化 / STRIPE-03）。
 *
 * <p>前缀 {@code payment.channel.adapters.stripe.sandbox}。</p>
 *
 * <h3>enabled 是装配门控，也是一键 kill switch</h3>
 * 默认 {@code false}——没有显式打开就绝不连 Stripe。与支付宝沙箱同口径（FR-133）：
 * 染成 {@code SANDBOX} 而本开关为 {@code false} ⇒ 400，<b>绝不静默回落 mock</b>（FR-241）。</p>
 *
 * <h3>密钥一律 env 注入（FR-290 / INV-2）</h3>
 * <ul>
 *   <li>{@code PAYMENT_STRIPE_SANDBOX_SECRET_KEY} —— {@code sk_test_...}</li>
 *   <li>{@code PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET} —— {@code whsec_...}（由 {@code stripe listen} 生成）</li>
 * </ul>
 * <b>禁硬编码、禁入库、禁明文日志</b>——{@link #toString()} 刻意只输出「配没配」。</p>
 *
 * <h3>为什么 webhook secret 在 enabled 时是<b>必需</b>项</h3>
 * 验签是回调三段式校验的第①段（FR-202）：没有它，任何伪造的
 * {@code checkout.session.completed} 都能把一笔没付钱的订单推进成已支付——
 * 那是直接可被利用的资金漏洞。故这里不做「缺失就跳过验签」的静默降级：
 * 缺密钥就拒绝启动，并明确告诉你去跑 {@code stripe listen}。</p>
 */
@Component
@ConfigurationProperties(prefix = "payment.channel.adapters.stripe.sandbox")
public class StripeSandboxProperties {

    /** 装配门控 / kill switch：默认关闭。 */
    private boolean enabled = false;

    /** 测试模式密钥（{@code sk_test_...}，仅 env 注入）。 */
    private String secretKey;

    /** Webhook 签名密钥（{@code whsec_...}，仅 env 注入）。 */
    private String webhookSecret;

    /** 付款完成后 Stripe 跳回的地址（<b>不承载资金事实</b>，仅买家体验）。 */
    private String successUrl = "http://localhost:8091/demo.html?stripe=success";

    /** 买家取消付款时跳回的地址。 */
    private String cancelUrl = "http://localhost:8091/demo.html?stripe=cancel";

    /** 连接超时（毫秒）。 */
    private long connectTimeoutMs = 5_000L;

    /** 读取超时（毫秒）。MUST 小于 {@code payment.reliability.timeout}（30s），理由同 FR-140。 */
    private long readTimeoutMs = 10_000L;

    /**
     * 启动期强校验（FR-134 同口径）：{@code enabled=true} 时缺失必需项 <b>拒绝启动</b>，
     * 且<b>一次列全</b>——否则运维要重启 N 次才能配齐。
     */
    @PostConstruct
    void validate() {
        if (!enabled) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(secretKey)) {
            missing.add("secretKey（env PAYMENT_STRIPE_SANDBOX_SECRET_KEY）");
        }
        if (isBlank(webhookSecret)) {
            missing.add("webhookSecret（env PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET；"
                    + "运行 `stripe listen --forward-to localhost:8084/internal/channels/STRIPE/callback` 获取）");
        }
        if (isBlank(successUrl) || isBlank(cancelUrl)) {
            missing.add("successUrl / cancelUrl");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            missing.add("connectTimeoutMs / readTimeoutMs（必须为正数）");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Stripe 沙箱已启用（payment.channel.adapters.stripe.sandbox.enabled=true）"
                            + "但以下必需配置缺失，拒绝启动：" + missing
                            + "。请通过环境变量注入（密钥严禁硬编码或入库，INV-2）。");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getSuccessUrl() {
        return successUrl;
    }

    public void setSuccessUrl(String successUrl) {
        this.successUrl = successUrl;
    }

    public String getCancelUrl() {
        return cancelUrl;
    }

    public void setCancelUrl(String cancelUrl) {
        this.cancelUrl = cancelUrl;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 刻意<b>不输出密钥</b>（INV-2）。 */
    @Override
    public String toString() {
        return "StripeSandboxProperties{enabled=" + enabled
                + ", secretKey=" + (isBlank(secretKey) ? "<未配>" : "<已配>")
                + ", webhookSecret=" + (isBlank(webhookSecret) ? "<未配>" : "<已配>")
                + ", successUrl=" + successUrl
                + ", cancelUrl=" + cancelUrl
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs + "}";
    }
}
