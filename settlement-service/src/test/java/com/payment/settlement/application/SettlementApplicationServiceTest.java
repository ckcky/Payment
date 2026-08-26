package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementStatus;
import com.payment.settlement.infra.InMemorySettlementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算批次编排测试（US3）：合格生成、重复幂等、商户不合格拒绝、未解决差异拒绝。
 */
class SettlementApplicationServiceTest {

    private final InMemorySettlementRepository repository = new InMemorySettlementRepository();
    private final InMemoryIdempotencyRegistry registry = new InMemoryIdempotencyRegistry();
    private final FakeMerchantClient merchantClient = new FakeMerchantClient();
    private final FakeReconciliationClient reconciliationClient = new FakeReconciliationClient();

    private SettlementApplicationService service() {
        return new SettlementApplicationService(repository, merchantClient, reconciliationClient, registry,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    @Test
    void eligibleMerchantCreatesBatchUnknownAfterSimulatedExecution() {
        SettlementBatch batch = service().createBatch("1", "2026-08", "idem-1");

        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.UNKNOWN);
        assertThat(batch.getMerchantId()).isEqualTo("1");
        assertThat(batch.getPeriod()).isEqualTo("2026-08");
        assertThat(batch.getIncomeMinor()).isEqualTo(5000L);
        assertThat(batch.getRefundMinor()).isEqualTo(1000L);
        assertThat(batch.getNetMinor()).isEqualTo(4000L);
        assertThat(batch.getItems()).hasSize(2);
    }

    @Test
    void duplicateIdempotencyKeyReturnsSameBatchId() {
        SettlementApplicationService svc = service();

        SettlementBatch first = svc.createBatch("1", "2026-08", "idem-1");
        SettlementBatch second = svc.createBatch("1", "2026-08", "idem-1");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void businessIdempotencyOnMerchantAndPeriodReturnsSameBatch() {
        SettlementApplicationService svc = service();

        SettlementBatch first = svc.createBatch("1", "2026-08", "idem-1");
        // 不同幂等键、相同商户+周期 → 业务幂等命中同一批次
        SettlementBatch second = svc.createBatch("1", "2026-08", "idem-2");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void ineligibleMerchantThrowsStateTransitionViolation() {
        merchantClient.view = new MerchantView(1L, "SUSPENDED", false);

        assertThatThrownBy(() -> service().createBatch("1", "2026-08", "idem-2"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void unresolvedDifferencesThrowStateTransitionViolation() {
        reconciliationClient.unresolvedDifferenceCount = 1;

        assertThatThrownBy(() -> service().createBatch("1", "2026-08", "idem-3"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void resolveBatchConvergesUnknownToSucceeded() {
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-1");

        SettlementBatch resolved = svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(resolved.getStatus()).isEqualTo(SettlementStatus.SUCCEEDED);
    }

    private static final class FakeMerchantClient implements MerchantClient {

        private MerchantView view = new MerchantView(1L, "ACTIVE", true);

        @Override
        public MerchantView getMerchant(Long merchantId) {
            return view;
        }
    }

    private static final class FakeReconciliationClient implements ReconciliationClient {

        private final List<SettlementFact> facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY"));
        private int unresolvedDifferenceCount = 0;

        @Override
        public ReconciliationSummary getSettlementSummary(String period) {
            return new ReconciliationSummary(period, facts, unresolvedDifferenceCount);
        }
    }
}
