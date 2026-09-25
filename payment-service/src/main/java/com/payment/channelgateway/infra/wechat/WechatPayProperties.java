package com.payment.channelgateway.infra.wechat;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信支付 V3 渠道配置（spec 039 / FR-008 / FR-009）。
 *
 * <p>前缀 {@code payment.wechat}——对应环境变量前缀 {@code PAYMENT_WECHAT_*}
 * （Spring 宽松绑定把 {@code mch-id} 映射为 {@code PAYMENT_WECHAT_MCH_ID}）。</p>
 *
 * <h3>{@code enabled} 只门控<b>真实模式</b>，<b>不</b>门控渠道注册（C-1 裁决 / FR-016）</h3>
 * 默认 {@code false}——没有显式打开就绝不连微信。但<b>插件始终注册</b>（与 Stripe 同构）：
 * {@code enabled=false} 时 WECHAT 仍在 {@code GET /internal/channels} 中出现（{@code enabled:false}），
 * MOCK 模态照常可用。理由：<b>「未启用真实模式」与「渠道不存在」是两件事</b>——
 * 混淆会让 MOCK 演示失去一个渠道，且弄红 {@code scenario-routing.sh} 的「已注册 WECHAT」硬断言。
 * 本类因此<b>不</b>得配合 {@code @ConditionalOnProperty} 做条件装配。</p>
 *
 * <h3>染色 SANDBOX 而 {@code enabled=false} ⇒ 400，绝不静默回落 mock（FR-241 / INV-8）</h3>
 * 与支付宝沙箱、Stripe 同口径。静默回落最坏的结果是「以为在测真实渠道、其实钱根本没出去」。</p>
 *
 * <h3>凭据一律 env 注入（FR-009 / INV-2）</h3>
 * <ul>
 *   <li>{@code PAYMENT_WECHAT_MCH_ID} —— 商户号</li>
 *   <li>{@code PAYMENT_WECHAT_APP_ID} —— 公众号 / 小程序 AppID</li>
 *   <li>{@code PAYMENT_WECHAT_API_V3_KEY} —— APIv3 密钥（32 字节，回调 AES-256-GCM 解密用）</li>
 *   <li>{@code PAYMENT_WECHAT_MERCHANT_SERIAL_NO} —— 商户 API 证书序列号</li>
 *   <li>{@code PAYMENT_WECHAT_PRIVATE_KEY} —— 商户私钥 PEM 内容（env 内联，与 {@code private-key-path} 二选一）</li>
 *   <li>{@code PAYMENT_WECHAT_PRIVATE_KEY_PATH} —— 商户私钥 PEM 文件路径（与 {@code private-key} 二选一）</li>
 *   <li>{@code PAYMENT_WECHAT_PLATFORM_CERT_PATH} —— 微信平台证书路径（验签用；与 {@code platform-public-key} 二选一）</li>
 *   <li>{@code PAYMENT_WECHAT_PLATFORM_PUBLIC_KEY} —— 微信平台公钥 PEM（新式验签；与 {@code platform-cert-path} 二选一）</li>
 *   <li>{@code PAYMENT_WECHAT_NOTIFY_URL} —— 回调地址（<b>资金事实的唯一权威来源</b>）</li>
 * </ul>
 * <b>禁硬编码、禁入库、禁明文日志</b>——{@link #toString()} 刻意只输出「配没配」。</p>
 *
 * <h3>启动期强校验（FR-008）</h3>
 * {@code enabled=true} 时必需项一旦缺失就<b>拒绝启动</b>，并<b>一次列全</b>缺失项——
 * 否则运维要重启 N 次才能配齐（同 {@code StripeSandboxProperties} 体例）。
 */
@Component
@ConfigurationProperties(prefix = "payment.wechat")
public class WechatPayProperties {

    /** 装配门控 / kill switch：默认关闭（<b>只门控真实模式</b>，不门控注册，C-1 裁决）。 */
    private boolean enabled = false;

    /** 微信 V3 API 基地址（可覆盖，供本地仿真桩指向自建桩）。 */
    private String apiBaseUrl = "https://api.mch.weixin.qq.com";

    /** 商户号（{@code mchid}）。 */
    private String mchId;

    /** 公众号 / 小程序 AppID（{@code appid}）。 */
    private String appId;

    /** APIv3 密钥（32 字节；回调 {@code resource} 的 AES-256-GCM 解密用）。 */
    private String apiV3Key;

    /** 商户 API 证书序列号（{@code serial_no}，签名用）。 */
    private String merchantSerialNo;

    /** 商户私钥 PEM 内容（<b>仅 env 注入</b>；与 {@link #privateKeyPath} 二选一）。 */
    private String privateKey;

    /** 商户私钥 PEM 文件路径（与 {@link #privateKey} 二选一；生产推荐挂载文件）。 */
    private String privateKeyPath;

    /** 微信平台证书 PEM 文件路径（验签用；与 {@link #platformPublicKey} 二选一）。 */
    private String platformCertPath;

    /** 微信平台公钥 PEM（新式验签；与 {@link #platformCertPath} 二选一）。 */
    private String platformPublicKey;

    /** 微信平台公钥 ID（{@code Wechatpay-Serial} 头比对用；配平台公钥时必需）。 */
    private String platformPublicKeyId;

    /**
     * 回调地址（{@code notify_url}）。
     *
     * <p>微信 V3 每个下单请求都要带 {@code notify_url}（与 Stripe 的全局 webhook 不同）。
     * 未配置时真实模式链路在下单<b>之前</b> 400——绝不出现「下单成功却永远收不到钱的通知」。</p>
     */
    private String notifyUrl;

    /** 连接超时（毫秒）。 */
    private long connectTimeoutMs = 5_000L;

    /** 读取超时（毫秒）。MUST 小于 {@code payment.reliability.timeout}（30s），理由同 FR-140。 */
    private long readTimeoutMs = 10_000L;

    /**
     * 启动期强校验（FR-008）：{@code enabled=true} 时缺失必需项 <b>拒绝启动</b>，
     * 且<b>一次列全</b>——否则运维要重启 N 次才能配齐。
     *
     * <p>{@code enabled=false} 时缺配置是<b>正常状态</b>（本地开发不配微信密钥），
     * 此时<b>不读任何 env</b>、不校验——报错反而会挡住启动。</p>
     */
    @PostConstruct
    void validate() {
        if (!enabled) {
            return; // 未启用：缺配置是预期状态，不读 env、不校验（C-1：也不影响注册）
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(mchId)) {
            missing.add("mchId（env PAYMENT_WECHAT_MCH_ID）");
        }
        if (isBlank(appId)) {
            missing.add("appId（env PAYMENT_WECHAT_APP_ID）");
        }
        if (isBlank(apiV3Key)) {
            missing.add("apiV3Key（env PAYMENT_WECHAT_API_V3_KEY，32 字节）");
        }
        if (isBlank(merchantSerialNo)) {
            missing.add("merchantSerialNo（env PAYMENT_WECHAT_MERCHANT_SERIAL_NO）");
        }
        if (isBlank(privateKey) && isBlank(privateKeyPath)) {
            missing.add("privateKey 或 privateKeyPath（env PAYMENT_WECHAT_PRIVATE_KEY / PAYMENT_WECHAT_PRIVATE_KEY_PATH，二选一）");
        }
        if (isBlank(platformCertPath) && isBlank(platformPublicKey)) {
            missing.add("platformCertPath 或 platformPublicKey（env PAYMENT_WECHAT_PLATFORM_CERT_PATH / "
                    + "PAYMENT_WECHAT_PLATFORM_PUBLIC_KEY，二选一；缺失则回调无法验签）");
        }
        if (isBlank(notifyUrl)) {
            missing.add("notifyUrl（env PAYMENT_WECHAT_NOTIFY_URL；缺失即「下单成功却收不到钱的通知」）");
        }
        if (isBlank(apiBaseUrl)) {
            missing.add("apiBaseUrl");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            missing.add("connectTimeoutMs / readTimeoutMs（必须为正数）");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "微信支付已启用（payment.wechat.enabled=true）但以下必需配置缺失，拒绝启动：" + missing
                            + "。请通过环境变量注入（密钥严禁硬编码或入库，INV-2）。");
        }
    }

    /** 是否具备可用商户私钥（内联或路径任一）。 */
    public boolean hasPrivateKey() {
        return !isBlank(privateKey) || !isBlank(privateKeyPath);
    }

    /** 是否具备可用平台验签材料（平台证书或平台公钥任一）。 */
    public boolean hasPlatformKey() {
        return !isBlank(platformCertPath) || !isBlank(platformPublicKey);
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

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getMchId() {
        return mchId;
    }

    public void setMchId(String mchId) {
        this.mchId = mchId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getApiV3Key() {
        return apiV3Key;
    }

    public void setApiV3Key(String apiV3Key) {
        this.apiV3Key = apiV3Key;
    }

    public String getMerchantSerialNo() {
        return merchantSerialNo;
    }

    public void setMerchantSerialNo(String merchantSerialNo) {
        this.merchantSerialNo = merchantSerialNo;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPlatformCertPath() {
        return platformCertPath;
    }

    public void setPlatformCertPath(String platformCertPath) {
        this.platformCertPath = platformCertPath;
    }

    public String getPlatformPublicKey() {
        return platformPublicKey;
    }

    public void setPlatformPublicKey(String platformPublicKey) {
        this.platformPublicKey = platformPublicKey;
    }

    public String getPlatformPublicKeyId() {
        return platformPublicKeyId;
    }

    public void setPlatformPublicKeyId(String platformPublicKeyId) {
        this.platformPublicKeyId = platformPublicKeyId;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public void setNotifyUrl(String notifyUrl) {
        this.notifyUrl = notifyUrl;
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

    /** 刻意<b>不输出密钥</b>（INV-2：密钥 MUST NOT 进明文日志）；只报「配没配」。 */
    @Override
    public String toString() {
        return "WechatPayProperties{enabled=" + enabled
                + ", apiBaseUrl=" + apiBaseUrl
                + ", mchId=" + (isBlank(mchId) ? "<未配>" : "<已配>")
                + ", appId=" + (isBlank(appId) ? "<未配>" : "<已配>")
                + ", apiV3Key=" + (isBlank(apiV3Key) ? "<未配>" : "<已配>")
                + ", merchantSerialNo=" + (isBlank(merchantSerialNo) ? "<未配>" : "<已配>")
                + ", privateKey=" + (hasPrivateKey() ? "<已配>" : "<未配>")
                + ", platformKey=" + (hasPlatformKey() ? "<已配>" : "<未配>")
                + ", notifyUrl=" + notifyUrl
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs + "}";
    }
}
