package com.payment.reconciliation.integration;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.StatementImportApplicationService;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.infra.CsvStatementParser;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.testsupport.ReconciliationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按周期对账的端到端编排场景（spec 006 T014 / FR-001 + spec 032 §10.1）：
 * 两个不同周期各自导入 classpath 真实账单 fixture（真 {@link CsvStatementParser} 解析），
 * 产出不同批次且 {@code statement_source} 各自正确、互不串期。
 *
 * <p>与 {@code ReconciliationSettlementRpcScenarioTest} 同风格：内存仓储 + fake 事实 +
 * 真实解析/导入链路，不连数据库。</p>
 */
class ReconciliationPeriodScenarioTest {

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
    private final StatementImportApplicationService importService = new StatementImportApplicationService(
            imports, new CsvStatementParser(), new NoopBusinessMetrics(), new StructuredAuditLogger());

    @BeforeEach
    void importFixtures() throws IOException {
        importService.importStatement("MOCK", "2026-08", "FILE",
                classpathFixture("2026-08-31.csv"), "test");
        importService.importStatement("MOCK", "2026-09", "FILE",
                classpathFixture("2026-09-30.csv"), "test");
    }

    private ReconciliationApplicationService service() {
        return ReconciliationTestSupport.service(repository, imports,
                period -> List.of(new PlatformFact("CH-AUD-0001", "PAYMENT", 10000L, "CNY", "SUCCEEDED")),
                period -> List.of(),
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    @Test
    void twoPeriodsProduceDistinctBatchesWithOwnStatementSource() {
        ReconciliationApplicationService service = service();

        ReconciliationBatch aug = service.runReconciliation("2026-08");
        ReconciliationBatch sep = service.runReconciliation("2026-09");

        assertThat(aug.getId()).isNotEqualTo(sep.getId());
        assertThat(aug.getStatementSource().sourceType()).isEqualTo("IMPORT");
        assertThat(sep.getStatementSource().sourceType()).isEqualTo("IMPORT");
        assertThat(aug.getStatementSource().locator()).isNotEqualTo(sep.getStatementSource().locator());
        assertThat(aug.getStatementSource().fallbackUsed()).isFalse();
        assertThat(sep.getStatementSource().fallbackUsed()).isFalse();
        assertThat(aug.getStatementSource().entryCount()).isEqualTo(5);
        assertThat(sep.getStatementSource().entryCount()).isEqualTo(5);
    }

    @Test
    void periodFixturesYieldDifferentDifferenceSets() {
        ReconciliationApplicationService service = service();

        ReconciliationBatch aug = service.runReconciliation("2026-08");
        ReconciliationBatch sep = service.runReconciliation("2026-09");

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

        ReconciliationBatch first = service.runReconciliation("2026-08");
        ReconciliationBatch again = service.runReconciliation("2026-08");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatementSource().locator())
                .isEqualTo(first.getStatementSource().locator());
    }

    private static String classpathFixture(String name) throws IOException {
        try (InputStream in = ReconciliationPeriodScenarioTest.class
                .getResourceAsStream("/fixtures/channel-statements/" + name)) {
            assertThat(in).as("fixture %s must exist on classpath", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
