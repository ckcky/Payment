package com.payment.channelgateway.infra.wechat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 039 / T1：{@code WechatPayProperties} 启动期强校验（FR-008 / FR-009 / INV-2）。
 *
 * <p>两组核心断言：
 * <ol>
 *   <li><b>{@code enabled=false} 时不读任何 env</b>——全空也不报错（本地开发常态），
 *       且不影响渠道注册（C-1 裁决）；</li>
 *   <li><b>{@code enabled=true} 时缺项拒绝启动</b>，且<b>一次列全</b>缺失项
 *       （照 {@code StripeSandboxProperties} / {@code AlipaySandboxProperties} 体例）。</li>
 * </ol>
 */
class WechatPayPropertiesTest {

    /** 构造一份「齐全」的配置，供各用例按需破坏其中一项。 */
    private static WechatPayProperties complete() {
        WechatPayProperties p = new WechatPayProperties();
        p.setEnabled(true);
        p.setMchId("1900000109");
        p.setAppId("wx1234567890abcdef");
        p.setApiV3Key("0123456789abcdef0123456789abcdef"); // 32 字节
        p.setMerchantSerialNo("5157F09EFDC096DE15EBE81A47057A72A");
        p.setPrivateKey("-----BEGIN PRIVATE KEY-----\nMIIEv...\n-----END PRIVATE KEY-----");
        p.setPlatformPublicKey("-----BEGIN PUBLIC KEY-----\nMIIBIj...\n-----END PUBLIC KEY-----");
        p.setPlatformPublicKeyId("PUB_KEY_ID_0001");
        p.setNotifyUrl("https://example.com/internal/channels/WECHAT/callback");
        return p;
    }

    // ---- enabled=false：不读任何 env，缺配置是预期状态 ----

    @Test
    @DisplayName("enabled=false ⇒ 全空也不报错（不读任何 env）[FR-008][C-1]")
    void disabledDoesNotReadEnv() {
        WechatPayProperties p = new WechatPayProperties(); // 全默认：enabled=false，字段全空

        assertThat(p.isEnabled()).isFalse();
        assertThatCode(p::validate).doesNotThrowAnyException();
        assertThat(p.hasPrivateKey()).isFalse();
        assertThat(p.hasPlatformKey()).isFalse();
    }

    // ---- enabled=true：缺项拒绝启动，一次列全 ----

    @Test
    @DisplayName("enabled=true 且全空 ⇒ 拒绝启动，并一次列全 mchId/appId/apiV3Key/serial/私钥/平台键/notifyUrl [FR-008]")
    void enabledWithNothingFailsAndListsAll() {
        WechatPayProperties p = new WechatPayProperties();
        p.setEnabled(true);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动")
                .hasMessageContaining("mchId")
                .hasMessageContaining("appId")
                .hasMessageContaining("apiV3Key")
                .hasMessageContaining("merchantSerialNo")
                .hasMessageContaining("privateKey")
                .hasMessageContaining("platformCertPath")
                .hasMessageContaining("notifyUrl");
    }

    @Test
    @DisplayName("enabled=true 缺 APIv3 密钥 ⇒ 精确指出该缺项 [FR-008]")
    void missingApiV3KeyIsReported() {
        WechatPayProperties p = complete();
        p.setApiV3Key(null);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiV3Key");
    }

    @Test
    @DisplayName("enabled=true 缺商户私钥（内联与路径皆空）⇒ 拒绝启动 [FR-009]")
    void missingPrivateKeyIsReported() {
        WechatPayProperties p = complete();
        p.setPrivateKey(null);
        p.setPrivateKeyPath(null);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("privateKey");
    }

    @Test
    @DisplayName("enabled=true 缺平台验签材料（证书与公钥皆空）⇒ 拒绝启动 [FR-008]")
    void missingPlatformKeyIsReported() {
        WechatPayProperties p = complete();
        p.setPlatformCertPath(null);
        p.setPlatformPublicKey(null);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platformCertPath");
    }

    @Test
    @DisplayName("enabled=true 缺 notifyUrl ⇒ 拒绝启动（否则「下单成功却收不到钱的通知」）[FR-008]")
    void missingNotifyUrlIsReported() {
        WechatPayProperties p = complete();
        p.setNotifyUrl(null);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notifyUrl");
    }

    @Test
    @DisplayName("enabled=true 超时非正 ⇒ 拒绝启动 [FR-008]")
    void nonPositiveTimeoutIsReported() {
        WechatPayProperties p = complete();
        p.setReadTimeoutMs(0);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readTimeoutMs");
    }

    // ---- 二选一字段：任一即可通过 ----

    @Test
    @DisplayName("私钥：内联 PEM 或文件路径，任一即可通过校验 [FR-009]")
    void privateKeyEitherInlineOrPath() {
        WechatPayProperties inline = complete();
        inline.setPrivateKeyPath(null);
        assertThatCode(inline::validate).doesNotThrowAnyException();

        WechatPayProperties byPath = complete();
        byPath.setPrivateKey(null);
        byPath.setPrivateKeyPath("/etc/payment/wechat/apiclient_key.pem");
        assertThatCode(byPath::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("平台验签材料：平台证书或平台公钥，任一即可通过校验 [FR-008]")
    void platformKeyEitherCertOrPublicKey() {
        WechatPayProperties byCert = complete();
        byCert.setPlatformPublicKey(null);
        byCert.setPlatformCertPath("/etc/payment/wechat/platform_cert.pem");
        assertThatCode(byCert::validate).doesNotThrowAnyException();

        WechatPayProperties byPublicKey = complete();
        byPublicKey.setPlatformCertPath(null);
        assertThatCode(byPublicKey::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("齐全配置 ⇒ 校验通过，且 hasPrivateKey / hasPlatformKey 为真 [FR-008]")
    void completeConfigPasses() {
        WechatPayProperties p = complete();

        assertThatCode(p::validate).doesNotThrowAnyException();
        assertThat(p.hasPrivateKey()).isTrue();
        assertThat(p.hasPlatformKey()).isTrue();
    }

    // ---- INV-2：密钥不得进明文日志 ----

    @Test
    @DisplayName("toString 不泄露密钥明文，只报「已配/未配」[INV-2]")
    void toStringDoesNotLeakSecrets() {
        WechatPayProperties p = complete();

        String s = p.toString();

        assertThat(s).doesNotContain("0123456789abcdef0123456789abcdef"); // apiV3Key
        assertThat(s).doesNotContain("MIIEv");                            // 私钥体
        assertThat(s).doesNotContain("MIIBIj");                           // 平台公钥体
        assertThat(s).contains("<已配>");
    }
}
