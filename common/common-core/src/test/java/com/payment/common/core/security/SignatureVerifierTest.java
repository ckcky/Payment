package com.payment.common.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HMAC-SHA256 验签与防重放（ADR-0025 / FR-001）。
 *
 * <p>验签是伪造回调的唯一防线，这里覆盖四类必须失败的情形：签名不匹配、body 被篡改、
 * 时间戳缺失/非法、时间戳超出重放窗口。</p>
 */
class SignatureVerifierTest {

    private static final String SECRET = "channel-secret-abc";
    private static final String BODY = "{\"status\":\"SUCCESS\",\"channelReference\":\"ch-1\"}";
    private static final long NOW = 1_800_000_000_000L;
    private static final long WINDOW_MS = 300_000L;

    @Test
    void matchingSignatureIsAccepted() {
        String timestamp = String.valueOf(NOW);
        String signature = SignatureVerifier.sign(SECRET, timestamp, BODY);

        assertThat(SignatureVerifier.verify(SECRET, timestamp, BODY, signature, NOW, WINDOW_MS)).isTrue();
    }

    @Test
    void signatureIsStableAndDeterministic() {
        String timestamp = String.valueOf(NOW);
        assertThat(SignatureVerifier.sign(SECRET, timestamp, BODY))
                .isEqualTo(SignatureVerifier.sign(SECRET, timestamp, BODY));
    }

    @Test
    void wrongSecretIsRejected() {
        String timestamp = String.valueOf(NOW);
        String signature = SignatureVerifier.sign("other-secret", timestamp, BODY);

        assertThat(SignatureVerifier.verify(SECRET, timestamp, BODY, signature, NOW, WINDOW_MS)).isFalse();
    }

    @Test
    void tamperedBodyIsRejected() {
        String timestamp = String.valueOf(NOW);
        String signature = SignatureVerifier.sign(SECRET, timestamp, BODY);

        // 签名基于 SUCCESS 计算，实际投递 FAILURE：金额/状态篡改必须被拦截
        assertThat(SignatureVerifier.verify(SECRET, timestamp,
                "{\"status\":\"FAILURE\",\"channelReference\":\"ch-1\"}", signature, NOW, WINDOW_MS)).isFalse();
    }

    @Test
    void missingOrBlankInputsAreRejected() {
        String timestamp = String.valueOf(NOW);
        String signature = SignatureVerifier.sign(SECRET, timestamp, BODY);

        assertThat(SignatureVerifier.verify(null, timestamp, BODY, signature, NOW, WINDOW_MS)).isFalse();
        assertThat(SignatureVerifier.verify(" ", timestamp, BODY, signature, NOW, WINDOW_MS)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, null, BODY, signature, NOW, WINDOW_MS)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, timestamp, BODY, null, NOW, WINDOW_MS)).isFalse();
    }

    @Test
    void nonNumericTimestampIsRejected() {
        assertThat(SignatureVerifier.verify(SECRET, "not-a-number", BODY, "sig", NOW, WINDOW_MS)).isFalse();
    }

    @Test
    void staleTimestampOutsideReplayWindowIsRejected() {
        String stale = String.valueOf(NOW - WINDOW_MS - 1);
        String signature = SignatureVerifier.sign(SECRET, stale, BODY);

        // 签名本身正确，但已超出重放窗口（防重放）
        assertThat(SignatureVerifier.verify(SECRET, stale, BODY, signature, NOW, WINDOW_MS)).isFalse();
    }

    @Test
    void timestampWithinWindowIsAcceptedOnBothSides() {
        String slightlyFuture = String.valueOf(NOW + WINDOW_MS - 1);
        assertThat(SignatureVerifier.verify(SECRET, slightlyFuture, BODY,
                SignatureVerifier.sign(SECRET, slightlyFuture, BODY), NOW, WINDOW_MS)).isTrue();

        String slightlyPast = String.valueOf(NOW - WINDOW_MS + 1);
        assertThat(SignatureVerifier.verify(SECRET, slightlyPast, BODY,
                SignatureVerifier.sign(SECRET, slightlyPast, BODY), NOW, WINDOW_MS)).isTrue();
    }
}
