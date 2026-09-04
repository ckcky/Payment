package com.payment.payment.application.reliability;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时收敛测试（spec US1 / FR-001/FR-002 / ADR-0004）：
 * PROCESSING 超阈值 → UNKNOWN（原因 TIMEOUT），且不触发成功回写（订单保持待支付）。
 */
class TimeoutScanTest {

    private TimeoutScanner scanner(TimeoutScannerHarness h, ReliabilityConfig cfg) {
        return new TimeoutScanner(h.payments, h.attempts, new NoopBusinessMetrics(), cfg);
    }

    private static final class TimeoutScannerHarness {
        final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
        final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    }

    private Payment processingPayment(long pid, long aid) {
        return Payment.rehydrate(pid, "PM-" + pid, "txn-" + pid, "order-" + pid, "user-1",
                100, "CNY", "idem-" + pid, PaymentStatus.PROCESSING, aid, null, 0, null, 0);
    }

    private PaymentAttempt attempt(long aid, long pid, Instant requestedAt) {
        return PaymentAttempt.rehydrate(aid, pid, "mock", 0,
                requestedAt, null, null, PaymentAttemptStatus.PENDING, null, null, 0);
    }

    @Test
    void processingBeyondThresholdBecomesUnknown() {
        ReliabilityConfig cfg = new ReliabilityConfig();
        cfg.setTimeout(Duration.ofSeconds(30));
        TimeoutScannerHarness h = new TimeoutScannerHarness();
        Payment p = processingPayment(1L, 10L);
        h.payments.save(p);
        h.attempts.save(attempt(10L, 1L, Instant.now().minusSeconds(120)));

        int n = scanner(h, cfg).scan(Instant.now());

        assertThat(n).isEqualTo(1);
        Payment reloaded = h.payments.findById(1L).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(reloaded.getFailureReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void recentProcessingIsNotTimedOut() {
        ReliabilityConfig cfg = new ReliabilityConfig();
        cfg.setTimeout(Duration.ofSeconds(30));
        TimeoutScannerHarness h = new TimeoutScannerHarness();
        Payment p = processingPayment(3L, 30L);
        h.payments.save(p);
        h.attempts.save(attempt(30L, 3L, Instant.now().minusSeconds(5)));

        int n = scanner(h, cfg).scan(Instant.now());

        assertThat(n).isEqualTo(0);
        assertThat(h.payments.findById(3L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void nonProcessingPaymentIsSkipped() {
        ReliabilityConfig cfg = new ReliabilityConfig();
        cfg.setTimeout(Duration.ofSeconds(30));
        TimeoutScannerHarness h = new TimeoutScannerHarness();
        Payment p = Payment.rehydrate(2L, "PM-2", "txn-2", "order-2", "user-1",
                100, "CNY", "idem-2", PaymentStatus.SUCCEEDED, 20L, null, 0, null, 0);
        h.payments.save(p);
        h.attempts.save(attempt(20L, 2L, Instant.now().minusSeconds(120)));

        int n = scanner(h, cfg).scan(Instant.now());

        assertThat(n).isEqualTo(0);
        assertThat(h.payments.findById(2L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void secondScanIsIdempotent() {
        ReliabilityConfig cfg = new ReliabilityConfig();
        cfg.setTimeout(Duration.ofSeconds(30));
        TimeoutScannerHarness h = new TimeoutScannerHarness();
        Payment p = processingPayment(4L, 40L);
        h.payments.save(p);
        h.attempts.save(attempt(40L, 4L, Instant.now().minusSeconds(120)));

        int first = scanner(h, cfg).scan(Instant.now());
        int second = scanner(h, cfg).scan(Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
        assertThat(h.payments.findById(4L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }
}
