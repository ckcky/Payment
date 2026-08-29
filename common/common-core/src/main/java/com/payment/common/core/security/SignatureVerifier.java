package com.payment.common.core.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 签名校验（ADR-0025）：渠道回调 / Mock 测试共用同一算法。
 *
 * <p>验签串 = {@code timestamp + "." + body}；采用常数时间比对防止时序侧信道泄露。</p>
 */
public final class SignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private SignatureVerifier() {
    }

    /** 对 {@code (timestamp, body)} 计算签名（渠道侧 / Mock 用于签名）。 */
    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return hex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("failed to sign payload", e);
        }
    }

    /**
     * 校验 {@code providedSignature} 是否与 {@code (timestamp, body)} 匹配，
     * 且 {@code timestamp} 落在 {@code [nowMillis-window, nowMillis+window]} 内（防重放）。
     */
    public static boolean verify(String secret, String timestamp, String body,
                                 String providedSignature, long nowMillis, long replayWindowMillis) {
        if (secret == null || secret.isBlank() || timestamp == null || providedSignature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowMillis - ts) > replayWindowMillis) {
            return false;
        }
        String expected = sign(secret, timestamp, body);
        return constantTimeEquals(expected, providedSignature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
