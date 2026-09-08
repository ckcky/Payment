package com.payment.reconciliation.integration;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.infra.CsvChannelStatementLoader;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按周期对账的端到端编排场景（spec 006 T014 / FR-001）：两个不同周期读到各自的 classpath 账单
 * fixture，产出不同批次且 {@code statement_source} 各自正确。
 *
 * <p>与 {@code ReconciliationSettlementRpcScenarioTest} 同风格：内存仓储 + fake 事实 + 真实
 * {@link CsvChannelStatementLoader}（读真实 fixture），不连数据库。</p>
 */
class ReconciliationPeriodScenarioTest {

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();

    private ReconciliationApplicationService service() {
        CsvChannelStatementLoader loader =
                new CsvChannelStatementLoader("fixtures/channel-statements", "sample.csv", "",
                        new NoopBusinessMetrics());
        return new ReconciliationApplicationService(
                repository,
                () -> List.of(new PlatformFact("CH-AUD-0001", "PAYMENT", 10000L, "CNY", "SUCCEEDED")),
                List::of,
                loader,
                new NoopBusinessMetrics(),
                new StructuredAuditLogger());
    }

    @Test
    void twoPeriodsProduceDistinctBatchesWithOwnStatementSource() {
        ReconciliationApplicationService service = service();

        ReconciliationBatch aug = service.runReconciliation("2026-08-31");
        ReconciliationBatch sep = service.runReconciliation("2026-09-30");

        assertThat(aug.getId()).isNotEqualTo(sep.getId());
        assertThat(aug.getStatementSource().locator()).contains("2026-08-31.csv");
        assertThat(sep.getStatementSource().locator()).contains("2026-09-30.csv");
        assertThat(aug.getStatementSource().fallbackUsed()).isFalse();
        assertThat(sep.getStatementSource().fallbackUsed()).isFalse();
    }

    @Test
    void periodFixturesYieldDifferentDifferenceSets() {
        ReconciliationApplicationService service = service();

        ReconciliationBatch aug = service.runReconciliation("2026-08-31");
        ReconciliationBatch sep = service.runReconciliation("2026-09-30");

        assertThat(aug.getDifferences()).extracting("reference")
                .containsExactlyInAnyOrder("CH-AUD-0002", "CH-AUD-0003", "CH-RF-0001", "CH-AUD-X1");
        assertThat(sep.getDifferences()).extracting("reference")
                .containsExactlyInAnyOrder("CH-AUD-0004", "CH-AUD-0005", "CH-RF-0002", "CH-AUD-X2");
        assertThat(aug.getMatches()).hasSize(1);
        assertThat(sep.getMatches()).hasSize(1);
    }

    @Test
    void rerunningSamePeriodReturnsTheStoredBatch() {
        ReconciliationApplicationService service = service();

        ReconciliationBatch first = service.runReconciliation("2026-08-31");
        ReconciliationBatch again = service.runReconciliation("2026-08-31");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatementSource().locator())
                .isEqualTo(first.getStatementSource().locator());
    }
}
