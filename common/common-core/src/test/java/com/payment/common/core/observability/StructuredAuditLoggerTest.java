package com.payment.common.core.observability;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 结构化资金审计日志（T071）：脱敏规则 + 审计方法不抛异常 + no-op 指标安全。
 */
class StructuredAuditLoggerTest {

    private final StructuredAuditLogger logger = new StructuredAuditLogger();

    @Test
    void maskReturnsAsterisksForNullOrShortValues() {
        assertThat(StructuredAuditLogger.mask(null)).isEqualTo("***");
        assertThat(StructuredAuditLogger.mask("1234")).isEqualTo("***");
    }

    @Test
    void maskKeepsFirstTwoAndLastTwoChars() {
        assertThat(StructuredAuditLogger.mask("4111111111111111")).isEqualTo("41***11");
        assertThat(StructuredAuditLogger.mask("4111111111111116")).isEqualTo("41***16");
    }

    @Test
    void auditDoesNotThrow() {
        assertThatCode(() -> logger.audit("payment.succeeded", "pay_1", 100L, "CNY",
                "PENDING", "SUCCESS", "payment", "payment-1")).doesNotThrowAnyException();
    }

    @Test
    void noopMetricsDoesNotThrow() {
        NoopBusinessMetrics metrics = new NoopBusinessMetrics();
        assertThatCode(() -> metrics.counter("payment.succeeded", 1.0, "module", "payment"))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics.timer("payment.duration", Duration.ofMillis(10), "module", "payment"))
                .doesNotThrowAnyException();
    }
}
