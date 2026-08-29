package com.payment.common.core.security;

/**
 * 敏感数据脱敏（ADR-0027）：密钥 / 凭证等敏感字段 MUST 脱敏后再进入日志与审计。
 *
 * <p>本项目当前无真实卡号 / 凭证，脱敏点先行落地，待接入真实支付渠道时统一复用。</p>
 */
public final class SensitiveDataMasker {

    private SensitiveDataMasker() {
    }

    /**
     * 保留前后各 4 位、中间 {@code ****}；长度 ≤ 8 或 null 整体返回 {@code ****}。
     */
    public static String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
