package com.payment.reconciliation.integration;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.application.ChannelStatementLoadResult;
import com.payment.reconciliation.application.ChannelStatementLoader;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账不得改写上游事实（spec 006 T024 / INV-6 / Constitution §III 边界 #4）：
 * 对账、差异处理、批次关闭是**只读事实**的动作，payment/refund 侧记录必须一字不动。
 */
class ReconciliationNoFactMutationTest {

    private final List<PlatformFact> paymentFacts = List.of(
            new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED"),
            new PlatformFact("pay-2", "PAYMENT", 2000L, "CNY", "SUCCEEDED"));
    private final List<PlatformFact> refundFacts = List.of(
            new PlatformFact("refund-1", "REFUND", 500L, "CNY", "SUCCEEDED"));
    private final List<ChannelStatement> statements = List.of(
            new ChannelStatement("pay-1", 1000L, "CNY", "SUCCEEDED"),
            new ChannelStatement("pay-2", 2000L, "CNY", "SUCCEEDED"),
            new ChannelStatement("channel-extra-1", 900L, "CNY", "SUCCEEDED"));

    private ReconciliationApplicationService service() {
        ChannelStatementLoader loader = period -> new ChannelStatementLoadResult(
                statements, ChannelStatementSource.fixture("inline", statements.size(), false));
        return new ReconciliationApplicationService(
                new InMemoryReconciliationRepository(),
                (PaymentFactsClient) () -> paymentFacts,
                (RefundFactsClient) () -> refundFacts,
                loader,
                new MicrometerBusinessMetrics(new SimpleMeterRegistry()),
                new StructuredAuditLogger());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentAndRefundFactsAreUnchangedAcrossFullLifecycle() {
        List<PlatformFact> paymentBefore = List.copyOf(paymentFacts);
        List<PlatformFact> refundBefore = List.copyOf(refundFacts);
        ReconciliationApplicationService service = service();

        ReconciliationBatch batch = service.runReconciliation("2026-08-31");
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

        ReconciliationBatch batch = service.runReconciliation("2026-08-31");

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
