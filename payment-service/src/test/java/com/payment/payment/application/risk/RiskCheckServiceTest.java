package com.payment.payment.application.risk;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最小风控规则（ADR-0028 / FR-006）：默认放行；开启后命中<b>只记录不阻断</b>。
 */
class RiskCheckServiceTest {

    private static final long LIMIT = 1_000L;

    @Test
    void disabledByDefaultPassesEverythingThrough() {
        RiskCheckService service = new RiskCheckService(false, LIMIT, 1,
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        assertThat(service.onPaymentCreated(payment(999_999L))).isEmpty();
    }

    @Test
    void amountAtLimitDoesNotTrigger() {
        // 阈值为「上限」，等于上限不算命中
        assertThat(enabled(LIMIT, 0).onPaymentCreated(payment(LIMIT))).isEmpty();
    }

    @Test
    void amountAboveLimitTriggersAndIsRecorded() {
        RecordingMetrics metrics = new RecordingMetrics();
        RiskCheckService service = new RiskCheckService(true, LIMIT, 0, metrics, new StructuredAuditLogger());

        List<String> hits = service.onPaymentCreated(payment(LIMIT + 1));

        assertThat(hits).containsExactly("SINGLE_MAX_AMOUNT");
        assertThat(metrics.names).containsExactly("payment.risk_triggered");
    }

    @Test
    void windowCountLimitTriggersAfterThreshold() {
        RiskCheckService service = enabled(0, 2);

        assertThat(service.onPaymentCreated(payment(1L))).isEmpty();
        assertThat(service.onPaymentCreated(payment(1L))).isEmpty();
        assertThat(service.onPaymentCreated(payment(1L))).containsExactly("WINDOW_LIMIT_COUNT");
    }

    /** 命中只记录指标，绝不抛异常、绝不改变资金主流程。 */
    @Test
    void hitNeverBlocksOrThrows() {
        RiskCheckService service = enabled(LIMIT, 1);

        Payment payment = payment(LIMIT + 1);
        assertThat(service.onPaymentCreated(payment)).isNotEmpty();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private static RiskCheckService enabled(long singleMax, int windowLimit) {
        return new RiskCheckService(true, singleMax, windowLimit,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    private static Payment payment(long amountMinor) {
        return Payment.rehydrate(1L, "txn-1", "order-1", "user-1", amountMinor, "CNY", "idem-1",
                PaymentStatus.PENDING, null, null, 0, null, null);
    }

    /** 只记录指标名的 {@link BusinessMetrics}。 */
    private static final class RecordingMetrics implements BusinessMetrics {

        private final List<String> names = new ArrayList<>();

        @Override
        public void counter(String name, double value, String... tags) {
            names.add(name);
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
            // 风控不涉及耗时指标
        }
    }
}
