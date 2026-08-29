package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.settlement.domain.AdjustmentDirection;
import com.payment.settlement.domain.SettlementAdjustment;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementItem;
import com.payment.settlement.domain.SettlementStatus;
import com.payment.settlement.infra.InMemorySettlementAdjustmentRepository;
import com.payment.settlement.infra.InMemorySettlementRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算批次编排测试（US3）：合格生成、重复幂等、商户不合格拒绝、未解决差异拒绝、
 * 调整项登记与带符号净额、记账触发、关闭、列出与 N5 幂等键错配。
 */
class SettlementApplicationServiceTest {

    private final InMemorySettlementRepository repository = new InMemorySettlementRepository();
    private final InMemorySettlementAdjustmentRepository adjustmentRepository =
            new InMemorySettlementAdjustmentRepository();
    private final FakeMerchantClient merchantClient = new FakeMerchantClient();
    private final FakeReconciliationClient reconciliationClient = new FakeReconciliationClient();
    private final FakeLedgerPostingGateway ledgerGateway = new FakeLedgerPostingGateway();

    private SettlementApplicationService service() {
        return new SettlementApplicationService(repository, merchantClient, reconciliationClient,
                adjustmentRepository, ledgerGateway, new NoopBusinessMetrics(), new StructuredAuditLogger());
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
        assertThat(batch.getFactCount()).isEqualTo(2);
        assertThat(batch.getSourcePeriod()).isEqualTo("2026-08");
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
    void idempotencyKeyReusedForDifferentMerchantThrowsDuplicate() {
        SettlementApplicationService svc = service();
        svc.createBatch("1", "2026-08", "idem-1");

        // 同幂等键、不同商户/周期 ⇒ N5 拒绝（MUST NOT 静默返回他商户批次）
        assertThatThrownBy(() -> svc.createBatch("2", "2026-09", "idem-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.DUPLICATE));
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
    void unknownFactTypeIsRejectedByGate() {
        reconciliationClient.facts = List.of(
                new SettlementFact("ref-1", "FEE", 5000L, "CNY"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY"));

        assertThatThrownBy(() -> service().createBatch("1", "2026-08", "idem-4"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    void createBatchWithNoReconciliationThrowsNotFound() {
        reconciliationClient.notFound = true;

        assertThatThrownBy(() -> service().createBatch("1", "2026-08", "idem-5"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void creditAdjustmentIncreasesNetAndAddsAdjustmentItem() {
        SettlementApplicationService svc = service();
        svc.registerAdjustment("1", "2026-08", "adj-1", 500L, AdjustmentDirection.CREDIT, "CNY", "补差", "ops-1");

        SettlementBatch batch = svc.createBatch("1", "2026-08", "idem-1");

        // 净额 = 收入 5000 − 退款 1000 + 调整 +500 = 4500
        assertThat(batch.getNetMinor()).isEqualTo(4500L);
        assertThat(batch.getAdjustmentMinor()).isEqualTo(500L);
        assertThat(batch.getItems()).hasSize(3);
        SettlementItem adjItem = batch.getItems().stream()
                .filter(i -> "ADJUSTMENT".equals(i.type())).findFirst().orElseThrow();
        assertThat(adjItem.amountMinor()).isEqualTo(500L);
    }

    @Test
    void debitAdjustmentDecreasesNet() {
        SettlementApplicationService svc = service();
        svc.registerAdjustment("1", "2026-08", "adj-2", 200L, AdjustmentDirection.DEBIT, "CNY", "客诉扣罚", "ops-1");

        SettlementBatch batch = svc.createBatch("1", "2026-08", "idem-1");

        // 净额 = 5000 − 1000 − 200 = 3800
        assertThat(batch.getNetMinor()).isEqualTo(3800L);
        assertThat(batch.getAdjustmentMinor()).isEqualTo(-200L);
    }

    @Test
    void registerAdjustmentAfterBatchExistsIsRejected() {
        SettlementApplicationService svc = service();
        svc.createBatch("1", "2026-08", "idem-1");

        // 批次已存在 ⇒ 建批后禁止追登调整项（快照语义）
        assertThatThrownBy(() -> svc.registerAdjustment("1", "2026-08", "adj-3", 100L,
                AdjustmentDirection.CREDIT, "CNY", "reason", "ops-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void registerAdjustmentSameKeySameParamsReturnsFirst() {
        SettlementApplicationService svc = service();
        SettlementAdjustment first = svc.registerAdjustment("1", "2026-08", "adj-4", 100L,
                AdjustmentDirection.CREDIT, "CNY", "reason", "ops-1");
        SettlementAdjustment second = svc.registerAdjustment("1", "2026-08", "adj-4", 100L,
                AdjustmentDirection.CREDIT, "CNY", "reason", "ops-1");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void registerAdjustmentSameKeyDifferentParamsRejected() {
        SettlementApplicationService svc = service();
        svc.registerAdjustment("1", "2026-08", "adj-5", 100L,
                AdjustmentDirection.CREDIT, "CNY", "reason", "ops-1");

        assertThatThrownBy(() -> svc.registerAdjustment("1", "2026-08", "adj-5", 200L,
                AdjustmentDirection.CREDIT, "CNY", "reason", "ops-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.DUPLICATE));
    }

    @Test
    void resolveBatchSucceededPostsToLedgerWhenNetPositive() {
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-1");

        svc.resolveBatch(created.getId(), "SUCCEEDED");

        // 应用层把批次幂等键透传给记账网关（SETTLEMENT: 前缀由网关内部拼装）
        assertThat(ledgerGateway.postedKeys).containsExactly("idem-1");
        assertThat(ledgerGateway.postedNet).containsExactly(4000L);
    }

    @Test
    void resolveBatchSucceededSkipsLedgerWhenNetNonPositive() {
        // 收入 1000 − 退款 1000 = 0 ⇒ 不发起记账
        reconciliationClient.facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 1000L, "CNY"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY"));
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-1");

        svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(ledgerGateway.postedKeys).isEmpty();
    }

    @Test
    void resolveBatchConvergesUnknownToSucceeded() {
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-1");

        SettlementBatch resolved = svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(resolved.getStatus()).isEqualTo(SettlementStatus.SUCCEEDED);
    }

    @Test
    void closeBatchFromSucceededTransitionsToClosed() {
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-1");
        svc.resolveBatch(created.getId(), "SUCCEEDED");

        SettlementBatch closed = svc.closeBatch(created.getId(), "ops-1");

        assertThat(closed.getStatus()).isEqualTo(SettlementStatus.CLOSED);
    }

    @Test
    void listBatchesFiltersByMerchantAndPeriod() {
        SettlementApplicationService svc = service();
        svc.createBatch("1", "2026-08", "idem-1");
        svc.createBatch("2", "2026-08", "idem-2");

        assertThat(svc.listBatches("1", null)).hasSize(1);
        assertThat(svc.listBatches("1", "2026-08")).hasSize(1);
        assertThat(svc.listBatches(null, null)).hasSize(2);
    }

    private static final class FakeMerchantClient implements MerchantClient {

        private MerchantView view = new MerchantView(1L, "ACTIVE", true);

        @Override
        public MerchantView getMerchant(Long merchantId) {
            return view;
        }
    }

    private static final class FakeReconciliationClient implements ReconciliationClient {

        private List<SettlementFact> facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY"));
        private int unresolvedDifferenceCount = 0;
        private boolean notFound = false;

        @Override
        public ReconciliationSummary getSettlementSummary(String period) {
            if (notFound) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "reconciliation not found for period: " + period);
            }
            return new ReconciliationSummary(period, facts, unresolvedDifferenceCount);
        }
    }

    private static final class FakeLedgerPostingGateway implements LedgerPostingGateway {

        private final List<String> postedKeys = new ArrayList<>();
        private final List<Long> postedNet = new ArrayList<>();

        @Override
        public void postSettlement(String idempotencyKey, Long batchId, long netMinor, String currencyCode) {
            postedKeys.add(idempotencyKey);
            postedNet.add(netMinor);
        }
    }
}
