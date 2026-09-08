package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事实读取失败（spec 006 T031 / ADR-0021 / INV-8）：失败上抛、**不落半成品批次**，
 * 周期可被安全重跑；失败本身必须可观测（{@code reconciliation.fact_read_failed}）。
 *
 * <p>「读不到事实却建了一个空批次」是对账最危险的假象——下次重跑会被周期幂等挡住，
 * 永远停在「本周期无差异」。</p>
 */
class ReconciliationFactReadFailureTest {

    private static final String PERIOD = "2026-08-31";

    private ChannelStatementLoader loader() {
        return period -> new ChannelStatementLoadResult(
                List.of(new ChannelStatement("pay-1", 1000L, "CNY", "SUCCEEDED")),
                ChannelStatementSource.fixture("inline", 1, false));
    }

    @Test
    void paymentFactFailureLeavesNoBatchBehind() {
        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReconciliationApplicationService service = new ReconciliationApplicationService(
                repository,
                () -> { throw new BizException("DOWNSTREAM_ERROR", "payment facts unavailable"); },
                List::of,
                loader(),
                new MicrometerBusinessMetrics(registry),
                new StructuredAuditLogger());

        assertThatThrownBy(() -> service.runReconciliation(PERIOD)).isInstanceOf(BizException.class);

        assertThat(repository.findByPeriod(PERIOD)).isEmpty();
        assertThat(registry.get("reconciliation.fact_read_failed")
                .tag("target", "payment").counter().count()).isEqualTo(1.0);
    }

    @Test
    void refundFactFailureLeavesNoBatchBehind() {
        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReconciliationApplicationService service = new ReconciliationApplicationService(
                repository,
                () -> List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                () -> { throw new BizException("DOWNSTREAM_ERROR", "refund facts unavailable"); },
                loader(),
                new MicrometerBusinessMetrics(registry),
                new StructuredAuditLogger());

        assertThatThrownBy(() -> service.runReconciliation(PERIOD)).isInstanceOf(BizException.class);

        assertThat(repository.findByPeriod(PERIOD)).isEmpty();
        assertThat(registry.get("reconciliation.fact_read_failed")
                .tag("target", "refund").counter().count()).isEqualTo(1.0);
    }

    @Test
    void periodCanBeSafelyRetriedAfterTransientFailure() {
        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
        AtomicInteger attempts = new AtomicInteger();
        ReconciliationApplicationService service = new ReconciliationApplicationService(
                repository,
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new BizException("DOWNSTREAM_ERROR", "transient");
                    }
                    return List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"));
                },
                List::of,
                loader(),
                new MicrometerBusinessMetrics(new SimpleMeterRegistry()),
                new StructuredAuditLogger());

        assertThatThrownBy(() -> service.runReconciliation(PERIOD)).isInstanceOf(BizException.class);

        var batch = service.runReconciliation(PERIOD);

        assertThat(batch.getPeriod()).isEqualTo(PERIOD);
        assertThat(repository.findByPeriod(PERIOD)).isPresent();
    }
}
