package com.payment.reconciliation.integration;

import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.reconciliation.api.ReconciliationSettlementFact;
import com.payment.reconciliation.api.ReconciliationSettlementSummaryResponse;
import com.payment.reconciliation.application.ChannelStatementLoader;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账→结算 RPC 场景集成测试（T061/T069）：用内存仓储 + 记录式 payment/refund/渠道账单 fake
 * 验证对账编排、差异处理与 settlement-summary 契约，供 settlement-service 消费。
 *
 * <p>reconciliation-service 只读平台事实，跨服务边界用 fake 替身，不连接真实服务。</p>
 */
class ReconciliationSettlementRpcScenarioTest {

    private final List<PlatformFact> consistentPayments = List.of(
            new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"),
            new PlatformFact("pay-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED"));
    private final List<PlatformFact> consistentRefunds = List.of(
            new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED"));
    private final List<ChannelStatement> consistentStatements = List.of(
            new ChannelStatement("pay-1", 1000L, "CNY", "SUCCEEDED"),
            new ChannelStatement("pay-2", 2000L, "CNY", "SUCCEEDED"),
            new ChannelStatement("refund-1", 500L, "CNY", "SUCCEEDED"));

    @Test
    void runReconciliationProducesConsistentBatch() {
        ReconciliationApplicationService service = service(consistentPayments, consistentRefunds, consistentStatements);

        ReconciliationBatch batch = service.runReconciliation("2026-08");

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
        List<ChannelStatement> statements = List.of(
                new ChannelStatement("pay-1", 900L, "CNY", "SUCCEEDED"),   // AMOUNT_MISMATCH
                new ChannelStatement("pay-2", 2000L, "CNY", "FAILED"),      // STATUS_MISMATCH
                new ChannelStatement("channel-extra-1", 999L, "CNY", "SUCCEEDED")); // CHANNEL_ONLY
        // refund-1 无渠道账单 → PLATFORM_ONLY

        ReconciliationApplicationService service = service(payments, List.of(), statements);

        ReconciliationBatch batch = service.runReconciliation("2026-08");

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
        List<PlatformFact> payments = List.of(
                new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"));
        List<ChannelStatement> statements = List.of(
                new ChannelStatement("pay-1", 900L, "CNY", "SUCCEEDED")); // AMOUNT_MISMATCH
        ReconciliationApplicationService service = service(payments, List.of(), statements);

        ReconciliationBatch batch = service.runReconciliation("2026-08");

        ReconciliationSettlementSummaryResponse before = service.settlementSummary("2026-08");
        assertThat(before.unresolvedDifferenceCount()).isEqualTo(1);

        service.resolveDifference(batch.getId(), "pay-1", "渠道金额修正");

        ReconciliationSettlementSummaryResponse after = service.settlementSummary("2026-08");
        assertThat(after.unresolvedDifferenceCount()).isZero();
    }

    @Test
    void settlementSummaryExposesMatchedFactsForSettlement() {
        ReconciliationApplicationService service = service(consistentPayments, consistentRefunds, consistentStatements);

        service.runReconciliation("2026-08");

        ReconciliationSettlementSummaryResponse summary = service.settlementSummary("2026-08");

        assertThat(summary.period()).isEqualTo("2026-08");
        assertThat(summary.unresolvedDifferenceCount()).isZero();
        assertThat(summary.facts()).extracting(ReconciliationSettlementFact::type)
                .containsExactlyInAnyOrder("PAYMENT", "PAYMENT", "REFUND");
        assertThat(summary.facts()).extracting(ReconciliationSettlementFact::amountMinor)
                .containsExactlyInAnyOrder(1000L, 2000L, 500L);
    }

    @Test
    void duplicateRunForSamePeriodReturnsSameBatch() {
        ReconciliationApplicationService service = service(consistentPayments, consistentRefunds, consistentStatements);

        ReconciliationBatch first = service.runReconciliation("2026-08");
        ReconciliationBatch second = service.runReconciliation("2026-08");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    private ReconciliationApplicationService service(List<PlatformFact> payments,
                                                    List<PlatformFact> refunds,
                                                    List<ChannelStatement> statements) {
        InMemoryReconciliationRepository repository = new InMemoryReconciliationRepository();
        InMemoryIdempotencyRegistry registry = new InMemoryIdempotencyRegistry();
        PaymentFactsClient paymentClient = () -> payments;
        RefundFactsClient refundClient = () -> refunds;
        ChannelStatementLoader loader = period -> statements;
        return new ReconciliationApplicationService(repository, paymentClient, refundClient, registry, loader,
                new NoopBusinessMetrics());
    }
}
