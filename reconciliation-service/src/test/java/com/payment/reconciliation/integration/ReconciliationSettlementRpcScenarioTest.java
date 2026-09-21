package com.payment.reconciliation.integration;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.api.ReconciliationSettlementFact;
import com.payment.reconciliation.api.ReconciliationSettlementSummaryResponse;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.testsupport.ReconciliationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.legacyLine;
import static com.payment.reconciliation.testsupport.ReconciliationTestSupport.seedImport;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账→结算 RPC 场景集成测试（T061/T069）：用内存仓储 + 记录式 payment/refund fake +
 * 导入账单验证对账编排、差异处理与 settlement-summary 契约，供 settlement-service 消费。
 *
 * <p>reconciliation-service 只读平台事实，跨服务边界用 fake 替身，不连接真实服务。</p>
 */
class ReconciliationSettlementRpcScenarioTest {

    private static final String PERIOD = "2026-08";

    private final List<PlatformFact> consistentPayments = List.of(
            new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"),
            new PlatformFact("pay-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED"));
    private final List<PlatformFact> consistentRefunds = List.of(
            new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED"));
    private final List<StatementLine> consistentStatements = List.of(
            legacyLine(1, "pay-1", 1000L, "SUCCEEDED"),
            legacyLine(2, "pay-2", 2000L, "SUCCEEDED"),
            legacyLine(3, "refund-1", 500L, "SUCCEEDED"));

    @Test
    void runReconciliationProducesConsistentBatch() {
        ReconciliationApplicationService service = service(consistentPayments, consistentRefunds, consistentStatements);

        ReconciliationBatch batch = service.runReconciliation(PERIOD);

        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(batch.getMatches()).hasSize(3);
        assertThat(batch.getDifferences()).isEmpty();
    }

    @Test
    void runReconciliationDetectsAllFourDifferenceTypes() {
        List<PlatformFact> payments = List.of(
                new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"),
                new PlatformFact("pay-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED"),
                new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED"));
        List<StatementLine> statements = List.of(
                legacyLine(1, "pay-1", 900L, "SUCCEEDED"),     // AMOUNT_MISMATCH
                legacyLine(2, "pay-2", 2000L, "FAILED"),       // STATUS_MISMATCH
                legacyLine(3, "channel-extra-1", 999L, "SUCCEEDED")); // CHANNEL_ONLY
        // refund-1 无渠道账单 → PLATFORM_ONLY

        ReconciliationApplicationService service = service(payments, List.of(), statements);

        ReconciliationBatch batch = service.runReconciliation(PERIOD);

        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.HAS_DIFFERENCE);
        assertThat(batch.getMatches()).isEmpty();
        assertThat(batch.getDifferences()).hasSize(4);
        assertThat(batch.getDifferences()).extracting("type").containsExactlyInAnyOrder(
                DifferenceType.AMOUNT_MISMATCH,
                DifferenceType.STATUS_MISMATCH,
                DifferenceType.PLATFORM_ONLY,
                DifferenceType.CHANNEL_ONLY);
    }

    @Test
    void resolveDifferenceMarksResolvedAndDropsUnresolvedCount() {
        List<StatementLine> statements = List.of(legacyLine(1, "pay-1", 900L, "SUCCEEDED")); // AMOUNT_MISMATCH
        ReconciliationApplicationService service = service(
                List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                List.of(), statements);

        ReconciliationBatch batch = service.runReconciliation(PERIOD);

        ReconciliationSettlementSummaryResponse before = service.settlementSummary(PERIOD);
        assertThat(before.unresolvedDifferenceCount()).isEqualTo(1);

        service.resolveDifference(batch.getId(), "pay-1", "渠道金额修正", "ops-1", null);

        ReconciliationSettlementSummaryResponse after = service.settlementSummary(PERIOD);
        assertThat(after.unresolvedDifferenceCount()).isZero();
    }

    @Test
    void settlementSummaryExposesMatchedFactsForSettlement() {
        ReconciliationApplicationService service = service(consistentPayments, consistentRefunds, consistentStatements);

        service.runReconciliation(PERIOD);

        ReconciliationSettlementSummaryResponse summary = service.settlementSummary(PERIOD);

        assertThat(summary.period()).isEqualTo(PERIOD);
        assertThat(summary.unresolvedDifferenceCount()).isZero();
        assertThat(summary.facts()).extracting(ReconciliationSettlementFact::type)
                .containsExactlyInAnyOrder("PAYMENT", "PAYMENT", "REFUND");
        assertThat(summary.facts()).extracting(ReconciliationSettlementFact::amountMinor)
                .containsExactlyInAnyOrder(1000L, 2000L, 500L);
    }

    @Test
    void duplicateRunForSamePeriodReturnsSameBatch() {
        ReconciliationApplicationService service = service(consistentPayments, consistentRefunds, consistentStatements);

        ReconciliationBatch first = service.runReconciliation(PERIOD);
        ReconciliationBatch second = service.runReconciliation(PERIOD);

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    private ReconciliationApplicationService service(List<PlatformFact> payments,
                                                     List<PlatformFact> refunds,
                                                     List<StatementLine> statements) {
        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
        InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
        PaymentFactsClient paymentClient = period -> payments;
        RefundFactsClient refundClient = period -> refunds;
        seedImport(imports, "MOCK", PERIOD, statements);
        return ReconciliationTestSupport.service(repository, imports, paymentClient, refundClient,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }
}
