package com.payment.common.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感数据脱敏（ADR-0027 / FR-003）：密钥、凭证等敏感字段 MUST 脱敏后再进入日志与审计。
 */
class SensitiveDataMaskerTest {

    @Test
    void keepsFirstAndLastFourCharacters() {
        assertThat(SensitiveDataMasker.maskToken("abcdefghijkl")).isEqualTo("abcd****ijkl");
    }

    @Test
    void fullyMasksShortAndNullTokens() {
        // 长度不足时连前后缀一起脱敏，避免短令牌被直接还原
        assertThat(SensitiveDataMasker.maskToken(null)).isEqualTo("****");
        assertThat(SensitiveDataMasker.maskToken("")).isEqualTo("****");
        assertThat(SensitiveDataMasker.maskToken("12345678")).isEqualTo("****");
    }

    @Test
    void maskedTokenNeverLeaksOriginalValue() {
        String secret = "PAYMENT_CHANNEL_SECRET_VALUE";
        String masked = SensitiveDataMasker.maskToken(secret);

        assertThat(masked).doesNotContain("CHANNEL_SECRET_VALUE");
        assertThat(masked).hasSize(12);
    }
}
