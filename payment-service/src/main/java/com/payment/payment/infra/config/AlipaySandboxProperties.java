package com.payment.payment.infra.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付宝沙箱配置（spec 030 / FR-133 / FR-134）。
 *
 * <p>前缀 {@code payment.channel.adapters.alipay.sandbox}。</p>
 *
 * <h3>enabled 是<b>装配门控</b>，也是<b>一键 kill switch</b>（FR-133）</h3>
 * 默认 {@code false}——没有显式打开就绝不连真实渠道。沙箱出问题时改一个配置即可全量停用，
 * 不需要重新发版。<b>若染色为 SANDBOX 而本开关为 false ⇒ 400 INVALID_ARGUMENT，
 * 绝不静默回落 mock</b>（FR-241 / INV-8）：静默回落会让调用方以为「在测沙箱」，
 * 实际上钱根本没进真实渠道——这种假象比直接报错危险得多。</p>
 *
 * <h3>启动期强校验（FR-134）</h3>
 * {@code enabled=true} 时必需项一旦缺失就<b>拒绝启动</b>，并<b>列出全部缺失项</b>。
 * 理由：等到第一笔沙箱支付失败才发现「appId 忘配了」，损失的是排障时间和一次真实交互；
 * 而启动期报错是零成本的。<b>一次列全</b>而非遇到第一个就抛——否则运维要重启 N 次才能配齐。
 *
 * <h3>密钥一律 env 注入（FR-290 / INV-2）</h3>
 * {@code app-private-key} / {@code alipay-public-key} 只能来自环境变量
 * （{@code PAYMENT_ALIPAY_SANDBOX_APP_PRIVATE_KEY} 等）。
 * <b>禁硬编码、禁入库、禁明文日志</b>——本类的 {@link #toString()} 刻意不输出它们。
 */
@Component
@ConfigurationProperties(prefix = "payment.channel.adapters.alipay.sandbox")
public class AlipaySandboxProperties {

    /** 装配门控 / kill switch：默认关闭，绝无误连真实渠道的可能。 */
    private boolean enabled = false;

    /** 沙箱网关地址（固定值，配置出来便于将来切换）。 */
    private String gatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    /** 应用 ID（沙箱应用）。 */
    private String appId;

    /** 应用私钥（PKCS8，PEM 内容；<b>仅 env 注入</b>）。 */
    private String appPrivateKey;

    /** 支付宝公钥（用于验签；<b>仅 env 注入</b>）。 */
    private String alipayPublicKey;

    /** 签名算法：沙箱一律 RSA2。 */
    private String signType = "RSA2";

    /** 数据格式。 */
    private String format = "json";

    /** 字符集。 */
    private String charset = "UTF-8";

    /**
     * 沙箱 HTTP 超时（毫秒，FR-140）。
     *
     * <p><b>MUST 小于 {@code payment.reliability.timeout}（30s）</b>：反向路径在请求线程内
     * 等渠道返回，若沙箱超时比整体超时还长，就会出现「整体超时先触发、渠道还在等」——
     * 结果是不确定态被记成确定态。默认 10s 留足 3 倍余量。</p>
     */
    private long httpTimeoutMs = 10_000L;

    /**
     * 启动期强校验（FR-134）。
     *
     * <p>只在 {@code enabled=true} 时校验——{@code false} 时缺配置是<b>正常状态</b>
     * （本地开发不配沙箱密钥），此时报错反而会挡住启动。</p>
     */
    @PostConstruct
    void validate() {
        if (!enabled) {
            return; // 未启用：缺配置是预期状态，不校验
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(appId)) {
            missing.add("appId（env PAYMENT_ALIPAY_SANDBOX_APP_ID）");
        }
        if (isBlank(appPrivateKey)) {
            missing.add("appPrivateKey（env PAYMENT_ALIPAY_SANDBOX_APP_PRIVATE_KEY）");
        }
        if (isBlank(alipayPublicKey)) {
            missing.add("alipayPublicKey（env PAYMENT_ALIPAY_SANDBOX_ALIPAY_PUBLIC_KEY）");
        }
        if (isBlank(gatewayUrl)) {
            missing.add("gatewayUrl");
        }
        if (httpTimeoutMs <= 0) {
            missing.add("httpTimeoutMs（必须为正数）");
        }
        if (!missing.isEmpty()) {
            // 一次列全：否则运维要重启 N 次才能配齐
            throw new IllegalStateException(
                    "支付宝沙箱已启用（payment.channel.adapters.alipay.sandbox.enabled=true）"
                            + "但以下必需配置缺失，拒绝启动：" + missing
                            + "。请通过环境变量注入（密钥严禁硬编码或入库，INV-2）。");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---- getters / setters（Spring Boot 宽松绑定用） ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppPrivateKey() {
        return appPrivateKey;
    }

    public void setAppPrivateKey(String appPrivateKey) {
        this.appPrivateKey = appPrivateKey;
    }

    public String getAlipayPublicKey() {
        return alipayPublicKey;
    }

    public void setAlipayPublicKey(String alipayPublicKey) {
        this.alipayPublicKey = alipayPublicKey;
    }

    public String getSignType() {
        return signType;
    }

    public void setSignType(String signType) {
        this.signType = signType;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(long httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }

    /**
     * 刻意<b>不输出密钥</b>（INV-2：密钥 MUST NOT 进明文日志）。
     *
     * <p>只报「配没配」——排障时需要知道的是这个，不是密钥内容本身。
     * 若将来有人图省事把它改成输出全部字段，这条注释就是那次改动的原始约束。</p>
     */
    @Override
    public String toString() {
        return "AlipaySandboxProperties{enabled=" + enabled
                + ", gatewayUrl=" + gatewayUrl
                + ", appId=" + (isBlank(appId) ? "<未配>" : appId)
                + ", appPrivateKey=" + (isBlank(appPrivateKey) ? "<未配>" : "<已配>")
                + ", alipayPublicKey=" + (isBlank(alipayPublicKey) ? "<未配>" : "<已配>")
                + ", signType=" + signType
                + ", httpTimeoutMs=" + httpTimeoutMs + "}";
    }
}
