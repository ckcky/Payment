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
                adjustmentRepository, ledgerGateway, AuditGateClient.disabled(),
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
                new SettlementFact("ref-1", "FEE", 5000L, "CNY", "1"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY", "1"));

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
    void merchantMissingFactIsRejectedByGate() {
        // 032/AC-3：事实缺 merchantId（归属未知）⇒ 拒绝结算，不落批次
        reconciliationClient.facts = List.of(new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY"));

        assertThatThrownBy(() -> service().createBatch("1", "2026-08", "idem-6"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(repository.findByIdempotencyKey("idem-6")).isEmpty();
    }

    @Test
    void otherMerchantFactsAreFilteredFromSettlementScope() {
        // 032/AC-3：归属他商户的事实滤出本商户结算口径（不进净额、不进 items），留痕不静默
        reconciliationClient.facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY", "1"),
                new SettlementFact("ref-other", "PAYMENT", 9000L, "CNY", "2"));

        SettlementBatch batch = service().createBatch("1", "2026-08", "idem-7");

        assertThat(batch.getIncomeMinor()).isEqualTo(5000L);
        assertThat(batch.getFactCount()).isEqualTo(1);
        assertThat(batch.getItems()).noneMatch(i -> "ref-other".equals(i.reference()));
    }

    @Test
    void unclosedDifferenceNetImpactIsWithheldFromSettlement() {
        // 032/G4 / TC-032-11：口径 = 全部已确认事实 − 未收口差异净影响；事实仍留在批次快照
        reconciliationClient.facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 10000L, "CNY", "1"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY", "1"));
        reconciliationClient.excludedFacts = List.of(
                new ExcludedSettlementFact("ref-1", "PAYMENT", 8000L, "AMOUNT_MISMATCH"));

        SettlementBatch batch = service().createBatch("1", "2026-08", "idem-8");

        // income = 10000 − 8000 = 2000，refund = 1000，net = 1000
        assertThat(batch.getIncomeMinor()).isEqualTo(2000L);
        assertThat(batch.getRefundMinor()).isEqualTo(1000L);
        assertThat(batch.getNetMinor()).isEqualTo(1000L);
        // 快照仍含全部已确认事实（差异扣减仅影响结算口径，不篡改事实记录）
        assertThat(batch.getFactCount()).isEqualTo(2);
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

        // spec 031 §9/M1：sourceId 用批次业务单号 batchNo（数值 batchId 不出边界）；幂等键由账本派生
        assertThat(ledgerGateway.postedBatchNos).containsExactly(created.getBatchNo());
        assertThat(ledgerGateway.postedNet).containsExactly(4000L);
    }

    @Test
    void resolveBatchSucceededPostsNegativeNetToLedger() {
        // H3（C-07）：净额为负同样记账（账本 §7.5 展开反向分录），不再静默跳过
        reconciliationClient.facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 1000L, "CNY", "1"),
                new SettlementFact("ref-2", "REFUND", 2500L, "CNY", "1"));
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-neg");

        svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(ledgerGateway.postedBatchNos).containsExactly(created.getBatchNo());
        assertThat(ledgerGateway.postedNet).containsExactly(-1500L);
    }

    @Test
    void resolveBatchSucceededSkipsLedgerWhenNetZero() {
        // 收入 1000 − 退款 1000 = 0 ⇒ 空批次不发事件（spec §7.5）
        reconciliationClient.facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 1000L, "CNY", "1"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY", "1"));
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-1");

        svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(ledgerGateway.postedBatchNos).isEmpty();
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
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY", "1"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY", "1"));
        private int unresolvedDifferenceCount = 0;
        private boolean notFound = false;
        private List<ExcludedSettlementFact> excludedFacts = List.of();

        @Override
        public ReconciliationSummary getSettlementSummary(String period) {
            if (notFound) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "reconciliation not found for period: " + period);
            }
            return new ReconciliationSummary(period, facts, excludedFacts, unresolvedDifferenceCount);
        }
    }

    private static final class FakeLedgerPostingGateway implements LedgerPostingGateway {

        private final List<String> postedBatchNos = new ArrayList<>();
        private final List<Long> postedNet = new ArrayList<>();

        @Override
        public void postMerchantSettlement(LedgerPostingGateway.MerchantSettlementFacts facts) {
            postedBatchNos.add(facts.batchNo());
            postedNet.add(facts.netMinor());
        }
    }
}
