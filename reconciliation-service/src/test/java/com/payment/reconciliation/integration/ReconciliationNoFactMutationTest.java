package com.payment.reconciliation.integration;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.testsupport.ReconciliationTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.legacyLine;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.seedImport;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账不得改写上游事实（spec 006 T024 / INV-6 / Constitution §III 边界 #4）：
 * 对账、差异处理、批次关闭是**只读事实**的动作，payment/refund 侧记录必须一字不动。
 */
class ReconciliationNoFactMutationTest {

    private static final String PERIOD = "2026-08-31";

    private final List<PlatformFact> paymentFacts = List.of(
            new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"),
            new PlatformFact("pay-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED"));
    private final List<PlatformFact> refundFacts = List.of(
            new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED"));
    private List<StatementLine> statements;

    private final InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
    private final InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();

    @BeforeEach
    void seedStatement() {
        statements = List.of(
                legacyLine(1, "pay-1", 1000L, "SUCCEEDED"),
                legacyLine(2, "pay-2", 2000L, "SUCCEEDED"),
                legacyLine(3, "channel-extra-1", 900L, "SUCCEEDED"));
        seedImport(imports, "MOCK", PERIOD, statements);
    }

    private ReconciliationApplicationService service() {
        return ReconciliationTestSupport.service(repository, imports,
                (PaymentFactsClient) period -> paymentFacts,
                (RefundFactsClient) period -> refundFacts,
                new MicrometerBusinessMetrics(new SimpleMeterRegistry()), new StructuredAuditLogger());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentAndRefundFactsAreUnchangedAcrossFullLifecycle() {
        List<PlatformFact> paymentBefore = List.copyOf(paymentFacts);
        List<PlatformFact> refundBefore = List.copyOf(refundFacts);
        ReconciliationApplicationService service = service();

        ReconciliationBatch batch = service.runReconciliation(PERIOD);
        for (Difference difference : List.copyOf(batch.getDifferences())) {
            service.resolveDifference(batch.getId(), difference.getReference(), "checked", "ops", null);
        }
        service.closeBatch(batch.getId(), "ops");

        assertThat(paymentFacts).containsExactlyElementsOf(paymentBefore);
        assertThat(refundFacts).containsExactlyElementsOf(refundBefore);
        assertThat(paymentFacts).extracting("reference")
                .containsExactly("pay-1", "pay-2");
        assertThat(refundFacts).extracting("reference")
                .containsExactly("refund-1");
    }

    /**
     * 差异只记录双侧快照，绝不回写上游：渠道多出的 channel-extra-1 记为 CHANNEL_ONLY
     * （平台侧金额为空），平台多出的 refund-1 记为 PLATFORM_ONLY（渠道侧金额为空）。
     */
    @Test
    void differenceRecordsCarryBothSideSnapshotWithoutMutation() {
        ReconciliationApplicationService service = service();

        ReconciliationBatch batch = service.runReconciliation(PERIOD);

        Difference channelOnly = batch.getDifferences().stream()
                .filter(d -> "channel-extra-1".equals(d.getReference()))
                .findFirst().orElseThrow();
        assertThat(channelOnly.getChannelAmountMinor()).isEqualTo(900L);
        assertThat(channelOnly.getPlatformAmountMinor()).isNull();

        Difference platformOnly = batch.getDifferences().stream()
                .filter(d -> "refund-1".equals(d.getReference()))
                .findFirst().orElseThrow();
        assertThat(platformOnly.getPlatformAmountMinor()).isEqualTo(500L);
        assertThat(platformOnly.getChannelAmountMinor()).isNull();

        // 平台侧没有这笔 ⇒ 不能凭空在平台事实里补一笔
        assertThat(paymentFacts).noneMatch(f -> "channel-extra-1".equals(f.reference()));
        assertThat(statements).noneMatch(s -> "refund-1".equals(s.reference()));
    }
}
