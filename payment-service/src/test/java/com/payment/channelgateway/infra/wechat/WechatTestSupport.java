package com.payment.channelgateway.infra.wechat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * 微信渠道测试的<b>共享密码学工具</b>（spec 039 / T4 / T5）。
 *
 * <p><b>密钥一律运行时生成</b>：本仓库纪律（FR-009 / INV-1）禁止任何私钥进 git，
 * 故测试不使用固定夹具密钥。代价是签名值不可写死，收益是不会有人把测试私钥当成真凭据。</p>
 */
final class WechatTestSupport {

    private WechatTestSupport() {
    }

    /** 生成 2048 位 RSA 密钥对（测试用，一次性）。 */
    static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate test rsa key pair", e);
        }
    }

    /** DER → PEM（64 字符换行）。{@code type} 取 {@code PRIVATE KEY} / {@code PUBLIC KEY}。 */
    static String toPem(String type, byte[] der) {
        String base64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return sb.append("-----END ").append(type).append("-----").toString();
    }

    /** RSA-SHA256 签名 → Base64（微信 V3 的两套签名——请求签名与回调验签——都走这个算法）。 */
    static String rsaSignBase64(PrivateKey key, String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign test message", e);
        }
    }

    /** RSA-SHA256 验签（Base64 签名串）。 */
    static boolean rsaVerify(java.security.PublicKey key, String message, String base64Signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception e) {
            return false;
        }
    }

    /** AES-256-GCM 加密（微信通知 {@code resource} 的形态）→ Base64。 */
    static String aesGcmEncryptBase64(String apiV3Key, String nonce, String associatedData, String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to encrypt test notification", e);
        }
    }

    /** 构造微信 V3 通知原始体（{@code resource} 已加密）。 */
    static String notificationBody(String apiV3Key, String resourceNonce, String associatedData,
                                   String plaintext) {
        String ciphertext = aesGcmEncryptBase64(apiV3Key, resourceNonce, associatedData, plaintext);
        return "{\"id\":\"EV-TEST-1\",\"create_time\":\"2026-09-25T17:00:00+08:00\","
                + "\"event_type\":\"TRANSACTION.SUCCESS\",\"resource_type\":\"encrypt-resource\","
                + "\"summary\":\"支付成功\","
                + "\"resource\":{\"original_type\":\"transaction\",\"algorithm\":\"AEAD_AES_256_GCM\","
                + "\"ciphertext\":\"" + ciphertext + "\","
                + "\"associated_data\":\"" + associatedData + "\","
                + "\"nonce\":\"" + resourceNonce + "\"}}";
    }
}
